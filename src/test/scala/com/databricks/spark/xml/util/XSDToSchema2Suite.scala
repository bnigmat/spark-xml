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

import com.databricks.spark.xml.TestUtils._
import org.apache.spark.sql.types._
import org.scalatest.funsuite.AnyFunSuite

class XSDToSchema2Suite extends AnyFunSuite {
  
  private val resDir = "src/test/resources"

  test("Basic parsing") {
    val schema = XSDToSchema2.read(Paths.get(s"$resDir/basket.xsd"), "basket")
    val parsedSchema = buildSchema(
      field("basket", schema,false)
    )
    val expectedSchema = buildSchema(
      field("basket",
        struct(
          structArray("entry",
            field("key"),
            field("value"))), nullable = false))
    assert(expectedSchema === parsedSchema)
  }

  test("Relative path parsing") {
    val schema = XSDToSchema2.read(Paths.get(s"$resDir/include-example/first.xsd"), "basket")
    val parsedSchema = buildSchema(
      field("basket", schema,false)
    )
    val expectedSchema = buildSchema(
      field("basket",
        struct(
          structArray("entry",
            field("key"),
            field("value"))), nullable = false))
    assert(expectedSchema === parsedSchema)
  }

  test("Test schema types and attributes") {
    val schema = XSDToSchema2.read(Paths.get(s"$resDir/catalog.xsd"))
    val parsedSchema = buildSchema(
      field("catalog", schema,false)
    )
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
    val parsedSchema = XSDToSchema2.read(Paths.get(s"$resDir/choice.xsd"))
    val expectedSchema = buildSchema(
      field("el", struct(field("foo"), field("bar"), field("baz")), nullable = false))
    assert(expectedSchema === parsedSchema)
  }

  test("Two root elements") {
    val parsedSchema = XSDToSchema2.read(Paths.get(s"$resDir/twoelements.xsd"))
    val expectedSchema = buildSchema(field("bar", nullable = false), field("foo", nullable = false))
    assert(expectedSchema === parsedSchema)
  }
  
  test("xs:any schema") {
    val parsedSchema = XSDToSchema2.read(Paths.get(s"$resDir/xsany.xsd"))
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
    val parsedSchema = XSDToSchema2.read(Paths.get(s"$resDir/long.xsd"))
    val expectedSchema = buildSchema(
      field("test",
        struct(field("userId", LongType, nullable = false)), nullable = false))
    assert(expectedSchema === parsedSchema)
  }

  test("Test xs:decimal type with restriction[fractionalDigits]") {
    val parsedSchema = XSDToSchema2.read(Paths.get(s"$resDir/decimal-with-restriction.xsd"))
    val expectedSchema = buildSchema(field("decimal_type_1", DecimalType(38, 18), nullable = false),
      field("decimal_type_2", DecimalType(38, 2), nullable = false))
    assert(parsedSchema === expectedSchema)
  }

  test("Complex schema parsing") {
    val parsedSchema = XSDToSchema2.read(Paths.get(s"$resDir/DeliveredMessage.xsd"), "deliveredMessage")
    val expectedSchema = buildSchema(
      field("objectId",StringType,true),
      field("lastModified",TimestampType,true),
      field("lastModifiedBy",StringType,true),
      field("deleted",BooleanType,true),
      field("objectCreationTime",TimestampType,true),
      field("deliveredDate",DateType,true),
      field("readDate",DateType,true),
      field("originalMessage",StringType,true),
      field("managingUser",StringType,true),
      field("sentDate",DateType,true),
      field("subjectText",StringType,true),
      field("contentText",StringType,true),
      field("contentMimeType",StringType,true),
      field("messageType",
        struct(
          field("deletableIndicator",BooleanType,true),
          field("messageTypeCode",StringType,true),
          field("messageTypeName",StringType,true)
        ),true
      ),
      structArray("containedAttachment",
        field("attachmentIdentifier", StringType, true),
        field("attachmentName",StringType,true),
        field("dsrsIdentifier",StringType,true)
      ),
      field("sendingSystemAccount",StringType,true)
    )
    assert(expectedSchema === parsedSchema)
  }

}
