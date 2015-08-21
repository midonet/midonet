/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.southbound.vtep.mock

import java.lang.{Boolean => JavaBoolean, Long => JavaLong}
import java.util

import org.junit.runner.RunWith
import org.opendaylight.ovsdb.lib.notation.{UUID => OvsdbUUID}
import org.opendaylight.ovsdb.lib.schema.ColumnType.KeyValuedColumnType
import org.opendaylight.ovsdb.lib.schema.{ColumnSchema, GenericTableSchema}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

@RunWith(classOf[JUnitRunner])
class MockOvsdbColumnTest extends FeatureSpec with Matchers {
    final val CName = "col_name"
    feature("single value columns") {
        scenario("uuid column") {
            val c: ColumnSchema[GenericTableSchema, OvsdbUUID] =
                MockOvsdbColumn.mkColumnSchema(CName, classOf[OvsdbUUID])
            c.getName shouldBe CName
            c.getType.getBaseType.toString shouldBe "UuidBaseType"
            c.getType.isMultiValued shouldBe false
            c.getType.getMin shouldBe 1
            c.getType.getMax shouldBe 1
        }
        scenario("integer column") {
            val c: ColumnSchema[GenericTableSchema, JavaLong] =
                MockOvsdbColumn.mkColumnSchema(CName, classOf[JavaLong])
            c.getName shouldBe CName
            c.getType.getBaseType.toString shouldBe "IntegerBaseType"
            c.getType.isMultiValued shouldBe false
            c.getType.getMin shouldBe 1
            c.getType.getMax shouldBe 1
        }
        scenario("string column") {
            val c: ColumnSchema[GenericTableSchema, String] =
                MockOvsdbColumn.mkColumnSchema(CName, classOf[String])
            c.getName shouldBe CName
            c.getType.getBaseType.toString shouldBe "StringBaseType"
            c.getType.isMultiValued shouldBe false
            c.getType.getMin shouldBe 1
            c.getType.getMax shouldBe 1
        }
        scenario("boolean column") {
            val c: ColumnSchema[GenericTableSchema, JavaBoolean] =
                MockOvsdbColumn.mkColumnSchema(CName, classOf[JavaBoolean])
            c.getName shouldBe CName
            c.getType.getBaseType.toString shouldBe "BooleanBaseType"
            c.getType.isMultiValued shouldBe false
            c.getType.getMin shouldBe 1
            c.getType.getMax shouldBe 1
        }
        scenario("unsupported column") {
            class SomeClass
            a [IllegalArgumentException] shouldBe thrownBy(
                MockOvsdbColumn.mkColumnSchema(CName, classOf[SomeClass]))
        }
    }

    feature("set columns") {
        scenario("uuid set column") {
            val c: ColumnSchema[GenericTableSchema, util.Set[_]] =
                MockOvsdbColumn.mkSetColumnSchema(CName, classOf[OvsdbUUID])
            c.getName shouldBe CName
            c.getType.getBaseType.toString shouldBe "UuidBaseType"
            c.getType.isMultiValued shouldBe true
            c.getType.getMin shouldBe 0
            c.getType.getMax shouldBe Long.MaxValue
        }
        scenario("integer set column") {
            val c: ColumnSchema[GenericTableSchema, util.Set[_]] =
                MockOvsdbColumn.mkSetColumnSchema(CName, classOf[JavaLong])
            c.getName shouldBe CName
            c.getType.getBaseType.toString shouldBe "IntegerBaseType"
            c.getType.isMultiValued shouldBe true
            c.getType.getMin shouldBe 0
            c.getType.getMax shouldBe Long.MaxValue
        }
        scenario("string set column") {
            val c: ColumnSchema[GenericTableSchema, util.Set[_]] =
                MockOvsdbColumn.mkSetColumnSchema(CName, classOf[String])
            c.getName shouldBe CName
            c.getType.getBaseType.toString shouldBe "StringBaseType"
            c.getType.isMultiValued shouldBe true
            c.getType.getMin shouldBe 0
            c.getType.getMax shouldBe Long.MaxValue
        }
        scenario("boolean set column") {
            val c: ColumnSchema[GenericTableSchema, util.Set[_]] =
                MockOvsdbColumn.mkSetColumnSchema(CName, classOf[JavaBoolean])
            c.getName shouldBe CName
            c.getType.getBaseType.toString shouldBe "BooleanBaseType"
            c.getType.isMultiValued shouldBe true
            c.getType.getMin shouldBe 0
            c.getType.getMax shouldBe Long.MaxValue
        }
        scenario("unsupported set column") {
            class SomeClass
            a [IllegalArgumentException] shouldBe thrownBy(
                MockOvsdbColumn.mkSetColumnSchema(CName, classOf[SomeClass]))
        }
    }

    feature("map columns") {
        scenario("uuid-uuid map column") {
            val c: ColumnSchema[GenericTableSchema, util.Map[_, _]] =
                MockOvsdbColumn.mkMapColumnSchema(CName, classOf[OvsdbUUID],
                                                  classOf[OvsdbUUID])
            c.getName shouldBe CName
            c.getType.getBaseType.toString shouldBe "UuidBaseType"
            c.getType.asInstanceOf[KeyValuedColumnType]
                .getKeyType.toString shouldBe "UuidBaseType"
            c.getType.isMultiValued shouldBe true
            c.getType.getMin shouldBe 0
            c.getType.getMax shouldBe Long.MaxValue
        }
        scenario("integer-uuid map column") {
            val c: ColumnSchema[GenericTableSchema, util.Map[_, _]] =
                MockOvsdbColumn.mkMapColumnSchema(CName, classOf[JavaLong],
                                                  classOf[OvsdbUUID])
            c.getName shouldBe CName
            c.getType.getBaseType.toString shouldBe "UuidBaseType"
            c.getType.asInstanceOf[KeyValuedColumnType]
                .getKeyType.toString shouldBe "IntegerBaseType"
            c.getType.isMultiValued shouldBe true
            c.getType.getMin shouldBe 0
            c.getType.getMax shouldBe Long.MaxValue
        }
        scenario("string-uuid map column") {
            val c: ColumnSchema[GenericTableSchema, util.Map[_, _]] =
                MockOvsdbColumn.mkMapColumnSchema(CName, classOf[String],
                                                  classOf[OvsdbUUID])
            c.getName shouldBe CName
            c.getType.getBaseType.toString shouldBe "UuidBaseType"
            c.getType.asInstanceOf[KeyValuedColumnType]
                .getKeyType.toString shouldBe "StringBaseType"
            c.getType.isMultiValued shouldBe true
            c.getType.getMin shouldBe 0
            c.getType.getMax shouldBe Long.MaxValue
        }
        scenario("uuid-integer map column") {
            val c: ColumnSchema[GenericTableSchema, util.Map[_, _]] =
                MockOvsdbColumn.mkMapColumnSchema(CName, classOf[OvsdbUUID],
                                                  classOf[JavaLong])
            c.getName shouldBe CName
            c.getType.getBaseType.toString shouldBe "IntegerBaseType"
            c.getType.asInstanceOf[KeyValuedColumnType]
                .getKeyType.toString shouldBe "UuidBaseType"
            c.getType.isMultiValued shouldBe true
            c.getType.getMin shouldBe 0
            c.getType.getMax shouldBe Long.MaxValue
        }
        scenario("integer-integer map column") {
            val c: ColumnSchema[GenericTableSchema, util.Map[_, _]] =
                MockOvsdbColumn.mkMapColumnSchema(CName, classOf[JavaLong],
                                                  classOf[JavaLong])
            c.getName shouldBe CName
            c.getType.getBaseType.toString shouldBe "IntegerBaseType"
            c.getType.asInstanceOf[KeyValuedColumnType]
                .getKeyType.toString shouldBe "IntegerBaseType"
            c.getType.isMultiValued shouldBe true
            c.getType.getMin shouldBe 0
            c.getType.getMax shouldBe Long.MaxValue
        }
        scenario("string-integer map column") {
            val c: ColumnSchema[GenericTableSchema, util.Map[_, _]] =
                MockOvsdbColumn.mkMapColumnSchema(CName, classOf[String],
                                                  classOf[JavaLong])
            c.getName shouldBe CName
            c.getType.getBaseType.toString shouldBe "IntegerBaseType"
            c.getType.asInstanceOf[KeyValuedColumnType]
                .getKeyType.toString shouldBe "StringBaseType"
            c.getType.isMultiValued shouldBe true
            c.getType.getMin shouldBe 0
            c.getType.getMax shouldBe Long.MaxValue
        }
        scenario("uuid-string map column") {
            val c: ColumnSchema[GenericTableSchema, util.Map[_, _]] =
                MockOvsdbColumn.mkMapColumnSchema(CName, classOf[OvsdbUUID],
                                                  classOf[String])
            c.getName shouldBe CName
            c.getType.getBaseType.toString shouldBe "StringBaseType"
            c.getType.asInstanceOf[KeyValuedColumnType]
                .getKeyType.toString shouldBe "UuidBaseType"
            c.getType.isMultiValued shouldBe true
            c.getType.getMin shouldBe 0
            c.getType.getMax shouldBe Long.MaxValue
        }
        scenario("integer-string map column") {
            val c: ColumnSchema[GenericTableSchema, util.Map[_, _]] =
                MockOvsdbColumn.mkMapColumnSchema(CName, classOf[JavaLong],
                                                  classOf[String])
            c.getName shouldBe CName
            c.getType.getBaseType.toString shouldBe "StringBaseType"
            c.getType.asInstanceOf[KeyValuedColumnType]
                .getKeyType.toString shouldBe "IntegerBaseType"
            c.getType.isMultiValued shouldBe true
            c.getType.getMin shouldBe 0
            c.getType.getMax shouldBe Long.MaxValue
        }
        scenario("string-string map column") {
            val c: ColumnSchema[GenericTableSchema, util.Map[_, _]] =
                MockOvsdbColumn.mkMapColumnSchema(CName, classOf[String],
                                                  classOf[String])
            c.getName shouldBe CName
            c.getType.getBaseType.toString shouldBe "StringBaseType"
            c.getType.asInstanceOf[KeyValuedColumnType]
                .getKeyType.toString shouldBe "StringBaseType"
            c.getType.isMultiValued shouldBe true
            c.getType.getMin shouldBe 0
            c.getType.getMax shouldBe Long.MaxValue
        }

        scenario("unsupported map key") {
            class SomeClass
            a[IllegalArgumentException] shouldBe thrownBy(
                MockOvsdbColumn.mkMapColumnSchema(CName, classOf[SomeClass],
                                                  classOf[OvsdbUUID]))
        }

        scenario("unsupported map value") {
            class SomeClass
            a[IllegalArgumentException] shouldBe thrownBy(
                MockOvsdbColumn.mkMapColumnSchema(CName, classOf[OvsdbUUID],
                                                  classOf[SomeClass]))
        }
    }
}
