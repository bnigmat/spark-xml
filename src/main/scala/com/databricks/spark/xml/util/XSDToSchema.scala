/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.databricks.spark.xml.util

import java.io.{File, FileInputStream, InputStreamReader, StringReader}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.collection.JavaConverters._
import org.apache.spark.annotation.Experimental
import org.apache.spark.sql.types._
import org.apache.ws.commons.schema._
import org.apache.ws.commons.schema.constants.Constants
import com.databricks.spark.xml.XmlOptions

/**
 * Utility to generate a Spark schema from an XSD.
 */
@Experimental
object XSDToSchema {

  /**
   * Reads a schema from an XSD file.
   * Note that if the schema consists of one complex parent type which you want to use as
   * the row tag schema, then you will need to extract the schema of the single resulting
   * struct in the resulting StructType, and use its StructType as your schema.
   *
   * @param xsdFile XSD file
   * @return Spark-compatible schema
   */
  @Experimental
  def read(xsdFile: File, rootElement: String = "", flattenRoot: Boolean = false): StructType = {
    val xmlSchemaCollection = new XmlSchemaCollection()
    xmlSchemaCollection.setBaseUri(xsdFile.getParent)
    val xmlSchema = xmlSchemaCollection.read(
      new InputStreamReader(new FileInputStream(xsdFile), StandardCharsets.UTF_8))
    if (flattenRoot){
      val dataType = getStructType(xmlSchema, rootElement)
      if (dataType.fields.length == 1 && dataType(0).dataType.isInstanceOf[StructType]){
        dataType(0).dataType.asInstanceOf[StructType]
      } else {
        dataType
      }
    } else {
      getStructType(xmlSchema, rootElement)
    }
  }

  /**
   * Reads a schema from an XSD file.
   * Note that if the schema consists of one complex parent type which you want to use as
   * the row tag schema, then you will need to extract the schema of the single resulting
   * struct in the resulting StructType, and use its StructType as your schema.
   *
   * @param xsdFile XSD file
   * @return Spark-compatible schema
   */
  @Experimental
  def read(xsdFile: Path): StructType = read(xsdFile.toFile)

  @Experimental
  def read(xsdFile: Path, rootElement: String): StructType = read(xsdFile.toFile, rootElement)

  @Experimental
  def read(xsdFile: Path, rootElement: String, flattenRoot: Boolean): StructType = read(xsdFile.toFile, rootElement, flattenRoot)

  /**
   * Reads a schema from an XSD as a string.
   * Note that if the schema consists of one complex parent type which you want to use as
   * the row tag schema, then you will need to extract the schema of the single resulting
   * struct in the resulting StructType, and use its StructType as your schema.
   *
   * @param xsdString XSD as a string
   * @return Spark-compatible schema
   */
  @Experimental
  def read(xsdString: String): StructType = {
    val xmlSchema = new XmlSchemaCollection().read(new StringReader(xsdString))
    getStructType(xmlSchema)
  }

  private def getStructField(xmlSchema: XmlSchema, schemaType: XmlSchemaType, parents: List[XmlSchemaType] = List()): StructField = {
    schemaType match {
      // xs:simpleType
      case simpleType: XmlSchemaSimpleType =>
        val schemaType = simpleType.getContent match {
          case restriction: XmlSchemaSimpleTypeRestriction =>
            val qName = simpleType.getQName match {
              case null => restriction.getBaseTypeName
              case n => n
            }
            qName match {
              case Constants.XSD_BOOLEAN => BooleanType
              case Constants.XSD_DECIMAL =>
                val scale = restriction.getFacets.asScala.collectFirst {
                  case facet: XmlSchemaFractionDigitsFacet => facet
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
        StructField("baseName", schemaType)

      // xs:complexType
      case complexType: XmlSchemaComplexType =>
        complexType.getContentModel match {
          case content: XmlSchemaSimpleContent =>
            // xs:simpleContent
            content.getContent match {
              case extension: XmlSchemaSimpleContentExtension =>
                val baseStructField = getStructField(xmlSchema,
                  xmlSchema.getParent.getTypeByQName(extension.getBaseTypeName), parents)
                val value = StructField("_VALUE", baseStructField.dataType)
                val attributes = getAttributeFields(xmlSchema, extension.getAttributes.asScala.toList)
                StructField(complexType.getName, StructType(value +: attributes))
              case unsupported =>
                throw new IllegalArgumentException(s"Unsupported content: $unsupported")
            }
          case complexContext: XmlSchemaComplexContent =>
            val childFields = complexContext.getContent match {
              case extension: XmlSchemaComplexContentExtension =>
                val baseFields: Seq[StructField] = if (extension.getBaseTypeName != null) {
                  xmlSchema.getTypeByName(extension.getBaseTypeName) match {
                    case complexType: XmlSchemaComplexType =>
                      getStructField(xmlSchema, complexType).dataType.asInstanceOf[StructType].fields
                    case simpleType: XmlSchemaSimpleType =>
                      Seq(getStructField(xmlSchema, simpleType))
                    case _ =>
                      Seq.empty
                  }
                } else Seq.empty
                extension.getParticle match {
                  case particle: XmlSchemaGroupParticle =>
                    baseFields ++ getParticleFields(xmlSchema, particle, parents)
                  case _ =>
                    if (extension.getAttributes.size() > 0){
                      baseFields ++ getAttributeFields(xmlSchema, extension.getAttributes.asScala.toList)
                    } else {
                      baseFields ++ Seq.empty
                    }
                }
              case _ =>
                Seq.empty
            }
            StructField(complexType.getName, StructType(childFields))
          case null =>
            val childFields = getParticleFields(xmlSchema, complexType.getParticle, parents)
            val attributes = getAttributeFields(xmlSchema, complexType.getAttributes.asScala.toList)
            StructField(complexType.getName, StructType(childFields ++ attributes))
          case unsupported =>
            throw new IllegalArgumentException(s"Unsupported content model: $unsupported")
        }
      case unsupported =>
        throw new IllegalArgumentException(s"Unsupported schema element type: $unsupported")
    }
  }

  private def getParticleFields(xmlSchema: XmlSchema, particle: XmlSchemaParticle, parents: List[XmlSchemaType] = List(), isChoice: Boolean = false): Seq[StructField] = {
    val childFields = particle match {

      case any: XmlSchemaAny =>
        val dataType = if (any.getMaxOccurs > 1) ArrayType(StringType) else StringType
        val nullable = any.getMinOccurs == 0 || isChoice
        Seq(StructField(XmlOptions.DEFAULT_WILDCARD_COL_NAME, dataType, nullable))

      case element: XmlSchemaElement =>
        if (element.getSchemaType != null) {
          val baseType = getStructField(xmlSchema, element.getSchemaType, parents ++ List(element.getSchemaType)).dataType
          val nullable = element.getMinOccurs == 0 || isChoice
          val dataType = if (element.getMaxOccurs > 1) ArrayType(baseType) else baseType
          Seq(StructField(element.getName, dataType, nullable))
        } else if (element.getRef != null && element.getRef.getTarget != null && element.getRef.getTarget.getSchemaType != null && element.getRef.getTarget.getSchemaType.isInstanceOf[XmlSchemaComplexType]){
          if (element.getRef.getTarget.getSchemaType.asInstanceOf[XmlSchemaComplexType].isAbstract){
            val abstractTypeName = element.getRef.getTarget.getName
            // Elements with substitutionGroup as abstract type
            val derivedTypes = xmlSchema.getSchemaTypes.values().iterator().asScala.toList
              .filter{x=> x.isInstanceOf[XmlSchemaComplexType] &&
                x.asInstanceOf[XmlSchemaComplexType].getContentModel != null &&
                x.asInstanceOf[XmlSchemaComplexType].getContentModel.getContent != null &&
                x.asInstanceOf[XmlSchemaComplexType].getContentModel.getContent.isInstanceOf[XmlSchemaComplexContentExtension] &&
                x.asInstanceOf[XmlSchemaComplexType].getContentModel.getContent.asInstanceOf[XmlSchemaComplexContentExtension]
                  .getBaseTypeName.getLocalPart.equals(abstractTypeName) }
            derivedTypes.map{ derivedType =>
              val dtEntry = xmlSchema.getElements.entrySet().asScala.toList.filter{entry => entry.getValue.getSchemaTypeName.equals(derivedType.getQName)}
              val fieldName = dtEntry.head.getKey.getLocalPart
              val schemaType = dtEntry.head.getValue.getSchemaType
              val baseType = getStructField(xmlSchema, schemaType, parents ++ List(schemaType)).dataType
              val dataType = if (dtEntry.head.getValue.getMaxOccurs > 1) ArrayType(baseType) else baseType
              StructField(fieldName, dataType, dtEntry.head.getValue.getMinOccurs == 0 || derivedTypes.size > 1)
            }
          } else {
            val baseType = getStructField(xmlSchema, element.getRef.getTarget.getSchemaType, parents ++ List(element.getRef.getTarget.getSchemaType)).dataType
            val dataType = if (element.getRef.getTarget.getMaxOccurs > 1) ArrayType(baseType) else baseType
            val nullable = element.getRef.getTarget.getMinOccurs == 0 || isChoice
            Seq(StructField(element.getRef.getTarget.getName, dataType, nullable))
          }
        } else if (element.getRef != null && element.getRef.getTarget != null && element.getRef.getTarget.getSchemaType != null && element.getRef.getTarget.getSchemaType.isInstanceOf[XmlSchemaSimpleType]){
          val baseType = getStructField(xmlSchema, element.getRef.getTarget.getSchemaType, parents ++ List(element.getRef.getTarget.getSchemaType)).dataType
          val dataType = if (element.getRef.getTarget.getMaxOccurs > 1) ArrayType(baseType) else baseType
          val nullable = element.getRef.getTarget.getMinOccurs == 0 || isChoice
          Seq(StructField(element.getRef.getTarget.getName, dataType, nullable))
        } else {
          Seq.empty
        }

      case particle: XmlSchemaGroupParticle =>
        particle match {
          case all: XmlSchemaAll =>
            all.getItems.asScala
              .filter{ item =>
                (item.isInstanceOf[XmlSchemaElement] && parents.filter(x=> item.asInstanceOf[XmlSchemaElement].getSchemaType eq x).size == 0) || !item.isInstanceOf[XmlSchemaElement]
              }
              .flatMap{ x =>
              getParticleFields(xmlSchema, x.asInstanceOf[XmlSchemaParticle], parents)
            }
          case choice: XmlSchemaChoice =>
            choice.getItems.asScala
              .filter{ item =>
                (item.isInstanceOf[XmlSchemaElement] && parents.filter(x=> item.asInstanceOf[XmlSchemaElement].getSchemaType eq x).size == 0) || !item.isInstanceOf[XmlSchemaElement]
              }
              .flatMap{ x =>
              getParticleFields(xmlSchema, x.asInstanceOf[XmlSchemaParticle], parents, true)
            }
          case sequence: XmlSchemaSequence =>
            sequence.getItems.asScala
              .filter{ item =>
                (item.isInstanceOf[XmlSchemaElement] && parents.filter(x=> item.asInstanceOf[XmlSchemaElement].getSchemaType eq x).size == 0) || !item.isInstanceOf[XmlSchemaElement]
              }
              .flatMap{ x =>
              getParticleFields(xmlSchema, x.asInstanceOf[XmlSchemaParticle], parents)
            }
          case _ =>
            Seq.empty
        }

      case ref: XmlSchemaGroupRef =>
        ref.getParticle match {
          case all: XmlSchemaAll =>
            all.getItems.asScala
              .filter{ item =>
                (item.isInstanceOf[XmlSchemaElement] && parents.filter(x=> item.asInstanceOf[XmlSchemaElement].getSchemaType eq x).size == 0) || !item.isInstanceOf[XmlSchemaElement]
              }
              .flatMap{ x =>
              getParticleFields(xmlSchema, x.asInstanceOf[XmlSchemaParticle], parents)
            }
          case choice: XmlSchemaChoice =>
            choice.getItems.asScala
              .filter{ item =>
                (item.isInstanceOf[XmlSchemaElement] && parents.filter(x=> item.asInstanceOf[XmlSchemaElement].getSchemaType eq x).size == 0) || !item.isInstanceOf[XmlSchemaElement]
              }
              .flatMap{ x =>
              getParticleFields(xmlSchema, x.asInstanceOf[XmlSchemaParticle], parents, true)
            }
          case sequence: XmlSchemaSequence =>
            sequence.getItems.asScala
              .filter{ item =>
                (item.isInstanceOf[XmlSchemaElement] && parents.filter(x=> item.asInstanceOf[XmlSchemaElement].getSchemaType eq x).size == 0) || !item.isInstanceOf[XmlSchemaElement]
              }
              .flatMap{ x =>
              getParticleFields(xmlSchema, x.asInstanceOf[XmlSchemaParticle], parents)
            }
          case _ =>
            Seq.empty
        }

      case _ =>
        Seq.empty
    }
    childFields
  }

  private def getAttributeFields(xmlSchema: XmlSchema, attrList: List[XmlSchemaAttributeOrGroupRef]): Seq[StructField] = {
    val attributes = attrList.flatMap { attr =>
      attr match {
        case attribute: XmlSchemaAttribute =>
          val attrType = attribute.getSchemaTypeName match {
            case null => StringType
            case t => getStructField(xmlSchema, xmlSchema.getParent.getTypeByQName(t)).dataType
          }
          Seq(StructField(s"_${attribute.getName}", attrType, attribute.getUse != XmlSchemaUse.REQUIRED))
        case ref: XmlSchemaAttributeGroupRef =>
          getAttributeFields(xmlSchema, ref.getRef.getTarget.getAttributes.asScala.map { x => x.asInstanceOf[XmlSchemaAttributeOrGroupRef] }.toList)
      }
    }.toSeq
    attributes
  }

  private def getStructType(xmlSchema: XmlSchema, rootElement: String = ""): StructType = {
    StructType(
      (if (rootElement.isEmpty) xmlSchema.getElements.asScala
        else xmlSchema.getElements.asScala.filter{entry => entry._2.getName.equalsIgnoreCase(rootElement)}
        ).toSeq.map { case (_, schemaElement) =>
          val schemaType = schemaElement.getSchemaType
          val rootType = getStructField(xmlSchema, schemaType, List(schemaType) )
          StructField(schemaElement.getName, rootType.dataType, schemaElement.getMinOccurs == 0)
      }
    )
  }

}
