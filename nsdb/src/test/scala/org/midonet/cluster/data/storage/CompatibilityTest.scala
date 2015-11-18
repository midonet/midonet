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

package org.midonet.cluster.data.storage

import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Matchers, FlatSpec}

import org.midonet.cluster.models.Topology.{PortGroup, Host}
import org.midonet.cluster.util.ProtobufUtil.protoFromTxt
import org.midonet.cluster.util.UUIDUtil._

@RunWith(classOf[JUnitRunner])
class CompatibilityTest extends FlatSpec with Matchers {

    private val random = new Random()

    "Host v5.0" should "be readable from current version" in {
        val id = randomUuidProto
        val name = random.nextString(10)
        val floodingProxyWeight = random.nextInt()
        val tunnelZoneId1 = randomUuidProto
        val tunnelZoneId2 = randomUuidProto
        val portId1 = randomUuidProto
        val portId2 = randomUuidProto

        val hostV5_0Txt = s"""
            id { $id }
            name: '$name'
            flooding_proxy_weight: $floodingProxyWeight
            tunnel_zone_ids { $tunnelZoneId1 }
            tunnel_zone_ids { $tunnelZoneId2 }
            port_ids { $portId1 }
            port_ids { $portId2 }
            """

        val host = protoFromTxt(hostV5_0Txt, Host.newBuilder())
            .asInstanceOf[Host]
        host.getId shouldBe id
        host.getName shouldBe name
        host.getFloodingProxyWeight shouldBe floodingProxyWeight
        host.getTunnelZoneIdsList should contain allOf (tunnelZoneId1, tunnelZoneId2)
        host.getPortIdsList should contain allOf (portId1, portId2)
    }

    "Port group v5.0" should "be readable from current version" in {
        val id = randomUuidProto
        val name = random.nextString(10)
        val tenantId = random.nextString(10)
        val stateful = random.nextBoolean()
        val portId1 = randomUuidProto
        val portId2 = randomUuidProto

        val portGroupV5_0Txt = s"""
            id { $id }
            name: '$name'
            tenant_id: '$tenantId'
            stateful: ${if (stateful) "true" else "false"}
            port_ids { $portId1 }
            port_ids { $portId2 }
            """

        val portGroup = protoFromTxt(portGroupV5_0Txt, PortGroup.newBuilder())
            .asInstanceOf[PortGroup]
        portGroup.getId shouldBe id
        portGroup.getName shouldBe name
        portGroup.getTenantId shouldBe tenantId
        portGroup.getStateful shouldBe stateful
        portGroup.getPortIdsList should contain allOf (portId1, portId2)
    }

}
