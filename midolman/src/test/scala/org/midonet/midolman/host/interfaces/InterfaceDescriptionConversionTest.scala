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

package org.midonet.midolman.host.interfaces

import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.State.HostState.Interface
import org.midonet.cluster.models.State.HostState.Interface.{Type => ProtoType, Endpoint => ProtoEndpoint, DpPortType => ProtoPortType}
import org.midonet.packets.MAC

import org.midonet.midolman.host.interfaces.InterfaceDescription.{Type => PojoType, Endpoint => PojoEndpoint}
import org.midonet.odp.DpPort.{Type => PojoPortType}

@RunWith(classOf[JUnitRunner])
class InterfaceDescriptionConversionTest extends FlatSpec with Matchers {

    private val random: Random = new Random

    private val typeToPojo = Map(
        ProtoType.PHYSICAL -> PojoType.PHYS,
        ProtoType.VIRTUAL -> PojoType.VIRT,
        ProtoType.TUNNEL -> PojoType.TUNN,
        ProtoType.UNKNOWN -> PojoType.UNKNOWN)

    private val endpointToPojo = Map(
        ProtoEndpoint.DATAPATH_EP -> PojoEndpoint.DATAPATH,
        ProtoEndpoint.PHYSICAL_EP -> PojoEndpoint.PHYSICAL,
        ProtoEndpoint.VM_EP -> PojoEndpoint.VM,
        ProtoEndpoint.GRE_EP -> PojoEndpoint.GRE,
        ProtoEndpoint.CAPWAP_EP -> PojoEndpoint.CAPWAP,
        ProtoEndpoint.LOCALHOST_EP -> PojoEndpoint.LOCALHOST,
        ProtoEndpoint.TUNTAP_EP -> PojoEndpoint.TUNTAP,
        ProtoEndpoint.UNKNOWN_EP -> PojoEndpoint.UNKNOWN
    )

    private val portTypeToPojo = Map(
        ProtoPortType.NET_DEV_DP -> PojoPortType.NetDev,
        ProtoPortType.INTERNAL_DP -> PojoPortType.Internal,
        ProtoPortType.GRE_DP -> PojoPortType.Gre,
        ProtoPortType.VXLAN_DP -> PojoPortType.VXLan,
        ProtoPortType.GRE64_DP -> PojoPortType.Gre64,
        ProtoPortType.LISP_DP -> PojoPortType.Lisp
    )

    "Interface" should "convert from Protocol Buffers" in {
        for (t <- ProtoType.values; ep <- ProtoEndpoint.values;
             pt <- ProtoPortType.values) {
            testFromProto(t, ep, pt)
        }
    }

    "Interface" should "convert to Protocol Buffers" in {
        for (t <- PojoType.values; ep <- PojoEndpoint.values;
             pt <- PojoPortType.values) {
            testToProto(t, ep, pt)
        }
    }

    private def testFromProto(t: ProtoType, ep: ProtoEndpoint, pt: ProtoPortType)
    : Unit = {
        val proto: Interface = Interface.newBuilder
            .setName(random.nextString(10))
            .setType(t)
            .setMac(MAC.random.toString)
            .setUp(random.nextBoolean())
            .setHasLink(random.nextBoolean())
            .setMtu(random.nextInt())
            .setEndpoint(ep)
            .setPortType(pt)
            .build()
        val pojo: InterfaceDescription = ZoomConvert
            .fromProto(proto, classOf[InterfaceDescription])
        assertEquals(pojo, proto)
    }

    private def testToProto(t: PojoType, ep: PojoEndpoint, pt: PojoPortType)
    : Unit = {
        val pojo = new InterfaceDescription(random.nextString(10))
        pojo.setType(t)
        pojo.setMac(MAC.random.toString)
        pojo.setUp(random.nextBoolean())
        pojo.setHasLink(random.nextBoolean())
        pojo.setMtu(random.nextInt())
        pojo.setEndpoint(ep)
        pojo.setPortType(pt)
        val proto = ZoomConvert.toProto(pojo, classOf[Interface])
        assertEquals(pojo, proto)
    }

    private def assertEquals(pojo: InterfaceDescription, proto: Interface)
    : Unit = {
        pojo.getName shouldBe proto.getName
        pojo.getType shouldBe typeToPojo(proto.getType)
        pojo.getMac.toString shouldBe proto.getMac
        pojo.isUp shouldBe proto.getUp
        pojo.hasLink shouldBe proto.getHasLink
        pojo.getMtu shouldBe proto.getMtu
        pojo.getEndpoint shouldBe endpointToPojo(proto.getEndpoint)
        pojo.getPortType shouldBe portTypeToPojo(proto.getPortType)
    }
}