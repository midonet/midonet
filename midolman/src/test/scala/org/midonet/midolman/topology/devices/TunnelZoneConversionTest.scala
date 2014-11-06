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
package org.midonet.midolman.topology.devices

import java.util.UUID

import scala.collection.JavaConversions._

import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology
import org.midonet.cluster.util.UUIDUtil._

class TunnelZoneConversionTest extends FeatureSpec with Matchers {

    feature("Conversion for tunnel zone of type GRE") {
        scenario("Test conversion from Protocol Buffer message") {
            val proto = newProto
                .setType(Topology.TunnelZone.Type.GRE)
                .build()
            val zoomObj = ZoomConvert.fromProto(proto, classOf[TunnelZone])

            zoomObj should not be None
            zoomObj.zoneType should be(TunnelZoneType.GRE)
            assertEquals(proto, zoomObj)
        }

        scenario("Test conversion to Protocol Buffer message") {
            val zoomObj = new TunnelZone
            zoomObj.zoneType = TunnelZoneType.GRE
            setZoomObjFields(zoomObj)
            val proto = ZoomConvert.toProto(zoomObj, classOf[Topology.TunnelZone])

            proto should not be None
            proto.getType should be(Topology.TunnelZone.Type.GRE)
            assertEquals(proto, zoomObj)
        }
    }

    feature("Conversion for tunnel zone of type VXLAN") {
        scenario("Test conversion from Protocol Buffer message") {
            val proto = newProto
                .setType(Topology.TunnelZone.Type.VXLAN)
                .build()
            val zoomObj = ZoomConvert.fromProto(proto, classOf[TunnelZone])

            zoomObj should not be None
            zoomObj.zoneType should be(TunnelZoneType.VXLAN)
            assertEquals(proto, zoomObj)
        }

        scenario("Test conversion to Protocol Buffer message") {
            val zoomObj = new TunnelZone
            zoomObj.zoneType = TunnelZoneType.VXLAN
            setZoomObjFields(zoomObj)
            val proto = ZoomConvert.toProto(zoomObj, classOf[Topology.TunnelZone])

            proto should not be None
            proto.getType should be(Topology.TunnelZone.Type.VXLAN)
            assertEquals(proto, zoomObj)
        }
    }

    feature("Conversion for tunnel zone of type VTEP") {
        scenario("Test conversion from Protocol Buffer message") {
            val proto = newProto
                .setType(Topology.TunnelZone.Type.VTEP)
                .build()
            val zoomObj = ZoomConvert.fromProto(proto, classOf[TunnelZone])

            zoomObj should not be None
            zoomObj.zoneType should be(TunnelZoneType.VTEP)
            assertEquals(proto, zoomObj)
        }
        scenario("Test conversion to Protocol Buffer message") {
            val zoomObj = new TunnelZone
            zoomObj.zoneType = TunnelZoneType.VTEP
            setZoomObjFields(zoomObj)
            val proto = ZoomConvert.toProto(zoomObj, classOf[Topology.TunnelZone])

            proto should not be None
            proto.getType should be(Topology.TunnelZone.Type.VTEP)
            assertEquals(proto, zoomObj)
        }
    }

    private def assertEquals(protoBuf: Topology.TunnelZone,
                            zoomObj: TunnelZone) = {
        protoBuf.getId.asJava should be (zoomObj.id)
        protoBuf.getName should be (zoomObj.name)
        protoBuf.getHostIdsList.size() should be(zoomObj.hostIds.size)
        protoBuf.getHostIdsList foreach(id => {
            zoomObj.hostIds should contain(id.asJava)
        })
    }

   private def newProto = {
       Topology.TunnelZone.newBuilder
       .setId(UUID.randomUUID.asProto)
       .setName("toto")
       .addHostIds(UUID.randomUUID().asProto)
       .addHostIds(UUID.randomUUID().asProto)
    }

   private def setZoomObjFields(zoomObj: TunnelZone) = {
       zoomObj.id = UUID.randomUUID()
       zoomObj.name = "toto"
       zoomObj.hostIds = Set(UUID.randomUUID(), UUID.randomUUID())
   }
}
