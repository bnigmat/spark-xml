/*
 * Copyright 2020 Databricks
 *
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

import java.nio.file.Paths

import org.apache.spark.sql.types.{ArrayType, BooleanType, DateType, DecimalType, FloatType, LongType, StringType, TimestampType}
import org.scalatest.funsuite.AnyFunSuite
import com.databricks.spark.xml.TestUtils._

import scala.io.Source

class XSDToSchemaSuite extends AnyFunSuite {
  
  private val resDir = "src/test/resources"

  test("Basic parsing") {
    val parsedSchema = XSDToSchema.read(Paths.get(s"$resDir/basket.xsd"))
    val expectedSchema = buildSchema(
      field("basket",
        struct(
          structArray("entry",
            field("key"),
            field("value"))), nullable = false))
    assert(expectedSchema === parsedSchema)
  }

  test("Relative path parsing") {
    val parsedSchema = XSDToSchema.read(Paths.get(s"$resDir/include-example/first.xsd"))
    val expectedSchema = buildSchema(
      field("basket",
        struct(
          structArray("entry",
            field("key"),
            field("value"))), nullable = false))
    assert(expectedSchema === parsedSchema)
  }

  test("Test schema types and attributes") {
    val parsedSchema = XSDToSchema.read(Paths.get(s"$resDir/catalog.xsd"))
    val expectedSchema = buildSchema(
      field("catalog",
        struct(
          field("product",
            struct(
              structArray("catalog_item",
                field("item_number", nullable = false),
                field("price", FloatType, nullable = false),
                structArray("size",
                  structArray("color_swatch",
                    field("_VALUE"),
                    field("_image")),
                  field("_description")),
                field("_gender")),
              field("_description"),
              field("_product_image")),
            nullable = false)),
        nullable = false))
    assert(expectedSchema === parsedSchema)
  }

  test("Test xs:choice nullability") {
    val parsedSchema = XSDToSchema.read(Paths.get(s"$resDir/choice.xsd"))
    val expectedSchema = buildSchema(
      field("el", struct(field("foo"), field("bar"), field("baz")), nullable = false))
    assert(expectedSchema === parsedSchema)
  }

  test("Two root elements") {
    val parsedSchema = XSDToSchema.read(Paths.get(s"$resDir/twoelements.xsd"))
    val expectedSchema = buildSchema(field("bar", nullable = false), field("foo", nullable = false))
    assert(expectedSchema === parsedSchema)
  }
  
  test("xs:any schema") {
    val parsedSchema = XSDToSchema.read(Paths.get(s"$resDir/xsany.xsd"))
    val expectedSchema = buildSchema(
      field("root",
        struct(
          field("foo",
            struct(
              field("xs_any")),
            nullable = false),
          field("bar",
            struct(
              field("xs_any", nullable = false)),
            nullable = false),
          field("baz",
            struct(
              field("xs_any", ArrayType(StringType), nullable = false)),
            nullable = false),
          field("bing",
            struct(
              field("xs_any")),
            nullable = false)),
        nullable = false))
    assert(expectedSchema === parsedSchema)
  }

  test("Tests xs:long type / Issue 520") {
    val parsedSchema = XSDToSchema.read(Paths.get(s"$resDir/long.xsd"))
    val expectedSchema = buildSchema(
      field("test",
        struct(field("userId", LongType, nullable = false)), nullable = false))
    assert(expectedSchema === parsedSchema)
  }

  test("Test xs:decimal type with restriction[fractionalDigits]") {
    val parsedSchema = XSDToSchema.read(Paths.get(s"$resDir/decimal-with-restriction.xsd"))
    val expectedSchema = buildSchema(field("decimal_type_1", DecimalType(38, 18), nullable = false),
      field("decimal_type_2", DecimalType(38, 2), nullable = false))
    assert(parsedSchema === expectedSchema)
  }

  test("Test exclude root") {
    val parsedSchema = XSDToSchema.read(Paths.get(s"$resDir/choice.xsd"), "", true)
    val expectedSchema = buildSchema(
      field("foo"),
      field("bar"),
      field("baz"))
    assert(expectedSchema === parsedSchema)
  }

  test("Test very complex schema parsing") {
    val parsedSchema = XSDToSchema.read(Paths.get(s"$resDir/abstract-example/event-logging.xsd")).json
    val source = Source.fromFile(Paths.get(s"$resDir/abstract-example/event-logging.json").toFile)
    val expectedSchema = try source.getLines().mkString("\n") finally source.close()
    assert(expectedSchema === parsedSchema)
  }

  test("Test schema with abstract types") {
    val parsedSchema = XSDToSchema.read(Paths.get(s"$resDir/abstract-example/DeliveredMessage.xsd"), "deliveredMessage" )
    val expectedSchema = buildSchema(
      field("deliveredMessage",
        struct(
          field("objectId",StringType,false),
          field("lastModified",TimestampType,false),
          field("lastModifiedBy",StringType,false),
          field("deleted",BooleanType,true),
          field("objectCreationTime",TimestampType,true),
          field("deliveredDate",DateType,false),
          field("readDate",DateType,true),
          field("originalMessage",StringType,false),
          field("managingUser",StringType,false),
          field("sentDate",DateType,false),
          field("subjectText",StringType,false),
          field("contentText",StringType,false),
          field("contentMimeType",StringType,false),
          field("messageType",
            struct(
              field("deletableIndicator",BooleanType,false),
              field("messageTypeCode",StringType,false),
              field("messageTypeName",StringType,false)
            ),false
          ),
          field("containedAttachment",
            ArrayType(
              struct(
                field("attachmentIdentifier", StringType, false),
                field("attachmentName",StringType,false),
                field("dsrsIdentifier",StringType,true)
              )
            ), true
          ),
          field("sendingSystemAccount",StringType,false)
        ), nullable = false
      )
    )
    assert(expectedSchema === parsedSchema)
  }

  test("Test substitutionGroup with abstract type") {
    val parsedSchema = XSDToSchema.read(Paths.get(s"$resDir/abstract-example/emf-event.xsd"), "emf-event").json
    val source = Source.fromFile(Paths.get(s"$resDir/abstract-example/emf-event.json").toFile)
    val expectedSchema = try source.getLines().mkString("\n") finally source.close()
    assert(expectedSchema === parsedSchema)
  }

  test("Test schema with circular references") {
    val parsedSchema = XSDToSchema.read(Paths.get(s"$resDir/abstract-example/Organization.xsd") )
    // Organization -> issuerUser -> organization
    val expectedSchema = buildSchema(
      field("organization",
        struct(
          field("objectId",StringType,false),
          field("lastModified",TimestampType,false),
          field("lastModifiedBy",StringType,false),
          field("deleted",BooleanType,true),
          field("objectCreationTime",TimestampType,true),
          field("organizationIdentifier",StringType,false),
          field("officeSymbolText",StringType,true),
          field("businessStatusDate",DateType,true),
          field("organizationURL",StringType,true),
          field("organizationType",StringType,false),
          field("organizationAddress",StringType,true),
          field("organizationTelephone",StringType,true),
          field("organizationBusinessStatus",StringType,false),
          field("organizationEmail",StringType,true),
          field("issuerUser",
            ArrayType(
              struct(
                field("emailAddressName", StringType, false),
                field("person",
                  struct(
                    field("objectId",StringType,false),
                    field("lastModified",TimestampType,false),
                    field("lastModifiedBy",StringType,false),
                    field("deleted",BooleanType,true),
                    field("objectCreationTime",TimestampType,true),
                    field("firstName", StringType, false),
                    field("middleName", StringType, true),
                    field("lastName", StringType, false),
                    field("salutationName",StringType,true),
                    field("suffixName",StringType,true),
                    field("birthDate",DateType,true),
                    field("countyName",StringType,true),
                    field("countyFipsCode",StringType,true),
                    field("zipPlus4Code",StringType,true),
                    field("streetName1",StringType,true),
                    field("streetName2",StringType,true),
                    field("cityName",StringType,true),
                    field("stateCode",StringType,true),
                    field("countryCode",StringType,true)
                  ), false),
                field("role",StringType,false),
                field("priority",StringType,false)
              )
            ), true
          )
        ), nullable = false
      )
    )
    assert(expectedSchema === parsedSchema)
  }
}
