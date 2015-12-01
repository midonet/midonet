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
package org.midonet.cluster.util

import java.util.{List => JList}
import java.util.{UUID => JUUID}

import org.scalatest.FlatSpec

import org.midonet.cluster.data.{ZoomField, ZoomClass, ZoomObject}
import org.midonet.cluster.models.TestModels.FakeDevice
import org.midonet.cluster.util.ListUtilTest.Device

class ListUtilTest extends FlatSpec {

    import ListUtil._
    import UUIDUtil._
    import scala.collection.JavaConversions._

    "List of Protocol Buffer messages" should "convert to list of objects" in {
        val listProto: JList[FakeDevice] =
            List(ListUtilTest.buildDevice,
                 ListUtilTest.buildDevice,
                 ListUtilTest.buildDevice)

        val listJava1 = ListUtil.fromProto(listProto, classOf[Device])
        assertList(listProto, listJava1)

        val listJava2 = listProto.asJava(classOf[Device])
        assertList(listProto, listJava2)
    }

    def assertList(listProto: JList[FakeDevice],
                   listPojo: JList[Device]): Unit = {
        assert(listProto.size == listPojo.size)
        for (index <- 0 until listProto.size) {
            assertDevice(listProto(index), listPojo(index))
        }
    }

    def assertDevice(proto: FakeDevice, pojo: Device): Unit = {
        assert(proto.getId.equals(pojo.id.asProto))
        assert(proto.getName.equals(pojo.name))
        assert(proto.getPortIdsCount == pojo.portIds.size)
        for (index <- 0 until proto.getPortIdsCount) {
            assert(proto.getPortIds(index).equals(pojo.portIds(index)))
        }
    }

}

object ListUtilTest {

    @ZoomClass(clazz = classOf[FakeDevice])
    class Device extends ZoomObject {
        @ZoomField(name = "id")
        val id: JUUID = null
        @ZoomField(name = "name")
        val name: String = null
        @ZoomField(name = "port_ids")
        val portIds: JList[String] = null
    }

    def buildDevice: FakeDevice = FakeDevice.newBuilder()
        .setId(UUIDUtil.randomUuidProto)
        .setName(UUIDUtil.randomUuidProto.toString)
        .addPortIds(UUIDUtil.randomUuidProto.toString)
        .addPortIds(UUIDUtil.randomUuidProto.toString)
        .build()

}
