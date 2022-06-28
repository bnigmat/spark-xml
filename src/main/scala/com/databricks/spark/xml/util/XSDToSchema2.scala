package com.databricks.spark.xml.util

import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.net.MalformedURLException
import java.nio.file.{Path, Paths}
import java.util
import java.util.stream.Collectors
import javax.xml.parsers.SAXParserFactory
import javax.xml.namespace.QName
import org.apache.spark.sql.types.{BooleanType, ByteType, DataType, DataTypes, DateType, DecimalType, DoubleType, FloatType, IntegerType, LongType, ShortType, StringType, StructField, StructType, TimestampType}
import com.databricks.spark.xml.XmlOptions
import com.sun.xml.xsom.{XSAttributeUse, XSElementDecl, XSFacet, XSModelGroup, XSModelGroupDecl, XSParticle, XSRestrictionSimpleType, XSSchemaSet, XSWildcard}
import com.sun.xml.xsom.parser.XSOMParser
import org.apache.ws.commons.schema.constants.Constants
import org.xml.sax.ErrorHandler
import org.xml.sax.{SAXException, SAXParseException}
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConverters.{asScalaIteratorConverter, seqAsJavaListConverter}
import scala.xml.{EntityResolver, InputSource}
import scala.util.control.Breaks._

/**
 * Utility to generate a Spark schema from one or multi-referenced (relative) XSDs.
 */
object XSDToSchema2 {

  case class ElementInfo(fieldName: String, elemType: DataType, isStruct: Boolean = false, isArray: Boolean = false, nullable: Boolean = true)

  /**
   * Reads a schema from an XSD file.
   *
   * @param xsdFile XSD file
   * @param rootElement Root element name
   * @param attributePrefix Attribute prefix, default: _
   * @return Spark-compatible schema
   */
  def read(xsdFile: File, rootElement: String, attributePrefix: String = XmlOptions.DEFAULT_ATTRIBUTE_PREFIX): StructType = {
    convert(xsdFile, rootElement, attributePrefix)
  }

  def read(xsdFile: Path, rootElement: String): StructType = read(xsdFile.toFile, rootElement)

  def read(xsdFile: Path): StructType = {
    val xsdName = xsdFile.getFileName.toString.split("\\.")(0)
    // Root element is taken from file name if not passed
    val rootElement = Character.toLowerCase(xsdName.charAt(0)) + xsdName.substring(1)
    read(xsdFile.toFile, rootElement)
  }

  private def convert(xsdFile: File, rootElement: String, attributePrefix: String = XmlOptions.DEFAULT_ATTRIBUTE_PREFIX): StructType = {
    val sset = getSchemaObject(xsdFile)
    val rootSchemaList = sset.getSchemas.iterator().asScala
      .filter( schema => !schema.getTargetNamespace.equalsIgnoreCase(Constants.URI_2001_SCHEMA_XSD) && schema.getElementDecls.containsKey(rootElement))
      .toList

    if (rootSchemaList.size > 0) {
      val xPathMap = new util.LinkedHashMap[String, ElementInfo]()
      val rootSchema = rootSchemaList.head

      val schemaType = rootSchema.getElementDecl(rootElement).getType
      if (schemaType.isComplexType){

        xPathMap.put("/" + rootElement, ElementInfo(rootElement, null, true))
        processParticle(schemaType.asComplexType.getContentType.asParticle, schemaType.asComplexType.getAttributeUses.iterator().asScala.toList, "/" + rootElement, xPathMap)
        val xPathList = xPathMap.entrySet().stream().collect(Collectors.toList())
        val rootRec = xPathList.iterator().next();
        val schema = getStructType(rootRec, xPathList.listIterator(), false, "_")
        schema.asInstanceOf[StructType]
      } else {
        StructType(Seq.empty)
      }
    } else {
      StructType(Seq.empty)
    }
  }

  private def getStructType(parentRec: util.Map.Entry[String, ElementInfo], iterator: util.ListIterator[util.Map.Entry[String, ElementInfo]], isArray: Boolean, attributePrefix: String): DataType = {
    val fieldsList = ArrayBuffer[StructField]()
    breakable {
      while ( iterator.hasNext ) {
        var curRec = iterator.next
        while ( iterator.hasNext && curRec.getKey.equalsIgnoreCase(parentRec.getKey))
          curRec = iterator.next
        val xPath = curRec.getKey
        val rec = curRec.getValue
        if (!xPath.startsWith(parentRec.getKey + "/")) {
          if (iterator.hasPrevious) iterator.previous
          break
        }
        val elemType = rec.elemType
        val fieldName = rec.fieldName
        var sqlType: DataType = null

        if (rec.isStruct && !rec.isArray){
          sqlType = getStructType(curRec, iterator,false, attributePrefix)
          fieldsList += StructField(fieldName, sqlType, true)
        } else if (rec.isStruct && rec.isArray){
          sqlType = getStructType(curRec, iterator,true, attributePrefix)
          fieldsList += StructField(fieldName, DataTypes.createArrayType(sqlType, true), true)
        } else if (!rec.isStruct && rec.isArray){
          fieldsList += StructField(fieldName, DataTypes.createArrayType(elemType, true), true)
        } else {
          fieldsList += StructField(fieldName, elemType, true)
        }
      }
    }
    DataTypes.createStructType(fieldsList.toArray)
  }

  private def processParticle(particle: XSParticle, attrUses: List[XSAttributeUse], xPath: String, xpathMap: util.Map[String, ElementInfo], attributePrefix: String = XmlOptions.DEFAULT_ATTRIBUTE_PREFIX): Unit = {
    if (attrUses != null) {
      for (attrUse <- attrUses) {
        val attrPath = xPath + "/" + attributePrefix + attrUse.getDecl.getName
        if (!xpathMap.containsKey(attrPath))
          xpathMap.put(attrPath, ElementInfo(attributePrefix + attrUse.getDecl.getName, StringType))
      }
    }
    val last2Tokens = xPath.replaceAll(".*(/.*.*)$", "$1")
    // Avoid circular references
    if (particle != null && getOccurences(xPath, last2Tokens) <= 1) {
      particle.getTerm match {
        case wildcard: XSWildcard =>
          // TODO: add code logic to process a wildcard
        case elementDecl: XSElementDecl =>
          val tp = elementDecl.getType
          if (tp.isComplexType) {
            if (elementDecl.getSubstitutables.size() > 0){
              for (subElementDecl <- elementDecl.getSubstitutables.iterator().asScala) {
                val subTp = subElementDecl.getType
                val cpXpath = xPath + "/" + subElementDecl.getName
                if (subTp.getName != null && subTp.getName.equals("anyType")){
                  if (!xpathMap.containsKey(cpXpath))
                    xpathMap.put(cpXpath, ElementInfo(subElementDecl.getName, StringType, false, particle.getMaxOccurs.intValue == -1 || particle.getMaxOccurs.intValue > 1, nullable = particle.getMinOccurs == 0))
                } else {
                  if (!xpathMap.containsKey(cpXpath))
                    xpathMap.put(cpXpath, ElementInfo(subElementDecl.getName, null, subTp.isComplexType, particle.getMaxOccurs.intValue == -1 || particle.getMaxOccurs.intValue > 1, nullable = particle.getMinOccurs == 0))
                  processParticle(subTp.asComplexType.getContentType.asParticle, null, xPath + "/" + subElementDecl.getName, xpathMap, attributePrefix)
                }
              }
            }
          } else {
            val schemaType = tp match {
              case simpleType: XSRestrictionSimpleType =>
                val typeName = simpleType.getName match {
                  case null => simpleType.getBaseType.getName
                  case n => n
                }
                val qName = new QName(Constants.URI_2001_SCHEMA_XSD,typeName)
                qName match {
                  case Constants.XSD_BOOLEAN => BooleanType
                  case Constants.XSD_DECIMAL =>
                    val scale = simpleType.getDeclaredFacets.iterator().asScala.filter(facet => facet.getName.equalsIgnoreCase("fractionDigits")).collectFirst {
                      case facet: XSFacet => facet
                    }
                    scale match {
                      case Some(scale) => DecimalType(38, scale.getValue.toString.toInt)
                      case None => DecimalType(38, 18)
                    }
                  case Constants.XSD_UNSIGNEDLONG => DecimalType(38, 0)
                  case Constants.XSD_DOUBLE => DoubleType
                  case Constants.XSD_FLOAT => FloatType
                  case Constants.XSD_BYTE => ByteType
                  case Constants.XSD_SHORT |
                       Constants.XSD_UNSIGNEDBYTE => ShortType
                  case Constants.XSD_INTEGER |
                       Constants.XSD_NEGATIVEINTEGER |
                       Constants.XSD_NONNEGATIVEINTEGER |
                       Constants.XSD_NONPOSITIVEINTEGER |
                       Constants.XSD_POSITIVEINTEGER |
                       Constants.XSD_UNSIGNEDSHORT => IntegerType
                  case Constants.XSD_LONG |
                       Constants.XSD_UNSIGNEDINT => LongType
                  case Constants.XSD_DATE => DateType
                  case Constants.XSD_DATETIME => TimestampType
                  case _ => StringType
                }
              case _ => StringType
            }
            val cpXpath = xPath + "/" + elementDecl.getName
            if (!xpathMap.containsKey(cpXpath)) {
              xpathMap.put(cpXpath, ElementInfo(elementDecl.getName, if (tp.isComplexType) null else schemaType, tp.isComplexType, particle.getMaxOccurs.intValue == -1 || particle.getMaxOccurs.intValue > 1, nullable = particle.getMinOccurs == 0))
            }
            if (tp.isComplexType)
              processParticle(tp.asComplexType.getContentType.asParticle, tp.asComplexType.getAttributeUses.iterator().asScala.toList, xPath + "/" + elementDecl.getName, xpathMap, attributePrefix)
          }

        case mg: XSModelGroup =>
          for (part <- mg.getChildren) {
            processParticle(part, null, xPath, xpathMap, attributePrefix)
          }
        case mgd: XSModelGroupDecl =>
          for (part <- mgd.getModelGroup.getChildren) {
            processParticle(part, null, xPath, xpathMap, attributePrefix)
          }
      }
    }
  }

  def getSchemaObject(xsdFile: File): XSSchemaSet = {
    var sset: XSSchemaSet = null
    val spf = SAXParserFactory.newInstance
    spf.setNamespaceAware(true)
    try {
      val parser = new XSOMParser(spf)
      val entityResolver = parser.getEntityResolver
      parser.setErrorHandler(new MyErrorHandler(System.err))
      parser.setEntityResolver(
        new EntityResolver() {
          @throws[SAXException]
          @throws[IOException]
          def resolveEntity(publicId: String, systemId: String): InputSource = {
            if (systemId != null)
              new InputSource(systemId)
            else if (entityResolver != null)
              entityResolver.resolveEntity(publicId, systemId)
            else
              null
          }
        }
      )
      val schemaFiles = Array(xsdFile)
      for (i <- 0 until schemaFiles.length) {
        val schemaFile = schemaFiles(i)
        val schemaUrl = schemaFile.toURI.toURL.toString
        val is = new InputSource(schemaUrl)
        parser.parse(is)
      }
      sset = parser.getResult
    } catch {
      case e: SAXException =>
        e.printStackTrace
      case e: MalformedURLException =>
        e.printStackTrace
    }
    sset
  }

  private def getOccurences(str: String, token: String): Int = {
    var counter = 0
    var index = str.indexOf(token)
    counter += (if (index >= 0) 1 else 0)
    while ( index >= 0 ) {
      index = str.indexOf(token, index + 1)
      counter += (if (index >= 0) 1 else 0)
    }
    counter
  }


  private class MyErrorHandler(var out: PrintStream) extends ErrorHandler {

    private def getParseExceptionInfo(spe: SAXParseException) = {
      var systemId = spe.getSystemId
      if (systemId == null) systemId = "null"
      val info = s"URI=${systemId} Line=${spe.getLineNumber}: ${spe.getMessage}"
      info
    }

    @throws[SAXException]
    def warning(spe: SAXParseException): Unit = {
      out.println("Warning: " + getParseExceptionInfo(spe))
    }

    @throws[SAXException]
    def error(spe: SAXParseException): Unit = {
      val message = "Error: " + getParseExceptionInfo(spe)
      throw new SAXException(message)
    }

    @throws[SAXException]
    def fatalError(spe: SAXParseException): Unit = {
      val message = "Fatal Error: " + getParseExceptionInfo(spe)
      throw new SAXException(message)
    }
  }
}
