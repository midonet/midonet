/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.cluster.data

import java.util.UUID

import scala.collection.JavaConversions._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.data.ZoomConvert.ConvertException
import org.midonet.cluster.models.TestModels
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{MapConverter, UUIDUtil}

/**
 * This class contains tests to ensure that conversion is done properly
 * when the ZoomObject has a field of type map. Other tests related to
 * conversion of zoom objects are performed in ZoomConvertTest.java
 */
@RunWith(classOf[JUnitRunner])
class ZoomMapConvertTest extends FeatureSpec with Matchers {

    feature("Conversion for zoom obj with non-parametrized key and value types") {
        scenario("Test conversion from Protocol Buffer message") {
            val proto = newProto
            val zoomObj = ZoomConvert.fromProto(proto, classOf[ZoomObjMap])

            zoomObj should not be(null)
            assertEquals(proto, zoomObj)
        }
        scenario("Test conversion to Protocol Buffer message") {
            val zoomObj = newZoomObjMap
            val proto = ZoomConvert.toProto(zoomObj, classOf[TestModels.TestMapMessage])

            proto should not be(null)
            assertEquals(proto, zoomObj)
        }
    }

    feature("Conversion for zoom obj with map and no map converter") {
        scenario("Test conversion from Protol Buffer message") {
            val proto = newProto
            intercept[ConvertException] {
                ZoomConvert.fromProto(proto, classOf[ZoomObjErroneousMap])
            }
        }
        scenario("Test conversion to Protocol Buffer message") {
            val zoomObjErrMap = newZoomObjErrMap
            intercept[ConvertException] {
                ZoomConvert.toProto(zoomObjErrMap, classOf[TestModels.TestMapMessage])
            }
        }
    }

    private def testKeyValue(value: String) = {
        TestModels.TestKeyValue.newBuilder
            .setId(UUID.randomUUID().asProto)
            .setValue(value)
            .build()
    }

    private def newProto = {
        TestModels.TestMapMessage.newBuilder
            .addMap(testKeyValue("titi"))
            .addMap(testKeyValue("foo"))
            .build()
    }

    private def newZoomObjMap = {
        val zoomObj = new ZoomObjMap
        zoomObj.map = Map(UUID.randomUUID() -> "titi",
                          UUID.randomUUID() -> "tata")
        zoomObj
    }

    private def newZoomObjErrMap = {
        val zoomObj = new ZoomObjErroneousMap
        zoomObj.map = Map(UUID.randomUUID() -> "titi",
                          UUID.randomUUID() -> "tata")
        zoomObj
    }

    private def assertEquals(proto: TestModels.TestMapMessage,
                             zoomObj: ZoomObjMap) = {
        proto.getMapCount should be(zoomObj.map.size)
        for (pair <- proto.getMapList) {
            val protoValue = pair.getValue
            val zoomObjValue = zoomObj.map.get(pair.getId.asJava)
            zoomObjValue should be(Some(protoValue))
        }
    }
}

class TestMapConverter extends MapConverter[UUID, String,
    TestModels.TestKeyValue] {
    def toKey(proto: TestModels.TestKeyValue): UUID = {
        UUIDUtil.fromProto(proto.getId)
    }

    def toValue(proto: TestModels.TestKeyValue): String = {
        proto.getValue
    }

    def toProto(key: UUID, value: String): TestModels.TestKeyValue = {
        TestModels.TestKeyValue.newBuilder.setId(UUIDUtil.toProto(key))
            .setValue(value).build
    }
}

@ZoomClass(clazz = classOf[TestModels.TestMapMessage])
class ZoomObjErroneousMap extends ZoomObject {

    @ZoomField(name = "map")
    var map: Map[UUID, String] = _
}

@ZoomClass(clazz = classOf[TestModels.TestMapMessage])
class ZoomObjMap extends ZoomObject {

    @ZoomField(name = "map", converter = classOf[TestMapConverter])
    var map: Map[UUID, String] = _
}