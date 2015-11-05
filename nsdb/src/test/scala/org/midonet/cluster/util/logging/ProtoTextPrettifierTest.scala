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

package org.midonet.cluster.util.logging

import java.util.UUID
import java.util.UUID.randomUUID

import scala.collection.JavaConversions._
import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FunSuite, ShouldMatchers}

import org.midonet.cluster.models.Commons.Int32Range
import org.midonet.cluster.models.Topology.{Dhcp, Network}
import org.midonet.cluster.util.logging.ProtoTextPrettifier._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}
import org.midonet.packets.{IPv4Addr, IPv6Addr}

@RunWith(classOf[JUnitRunner])
class ProtoTextPrettifierTest extends FunSuite with ShouldMatchers {

    test("Non proto UUID") {
        val uuid = randomUUID()
        val serialized = makeReadable(uuid)
        uuid.toString shouldBe serialized
    }

    test("UUID") {
        val uuid = randomUUID()
        val serialized = makeReadable(UUIDUtil.toProto(uuid))
        uuid.toString shouldBe serialized
    }

    test("IPv4") {
        val ipv4 = IPv4Addr.random
        val serialized = makeReadable(IPAddressUtil.toProto(ipv4))
        ipv4.toString shouldBe serialized
    }

    test("IPv6") {
        val ipv6 = IPv6Addr.random
        val serialized = makeReadable(IPAddressUtil.toProto(ipv6))
        ipv6.toString shouldBe serialized
    }

    test("Ranges") {
        val start = Random.nextInt(100)
        val end = start + Random.nextInt(1000)
        val r = Int32Range.newBuilder.setStart(start).setEnd(end).build()
        s"[$start, $end]" shouldBe makeReadable(r)
    }

    test("Null") {
        intercept[NullPointerException] {
            makeReadable(null)
        }
    }

    test("Repeated fields that are messages") {
        val r = Network.newBuilder().setName("test")
        1 to 3 foreach { _ => r.addPortIds(UUIDUtil.toProto(randomUUID())) }

        val serialized = makeReadable(r.build())

        serialized shouldNot be(empty)

        r.getPortIdsList.map { UUIDUtil.fromProto } foreach { id =>
            serialized.contains(id.toString) shouldBe true
        }
    }

    test("A more complex proto with nested objects") {
        val dhcpSubnet = IPSubnetUtil.toProto("10.0.0.0/18")
        val gw = IPAddressUtil.toProto("10.0.0.1")
        val id = UUID.randomUUID()
        val dhcp = Dhcp.newBuilder()
                       .setId(UUIDUtil.toProto(id))

        val opt121 = dhcp.addOpt121RoutesBuilder()
                         .setDstSubnet(dhcpSubnet)
                         .setGateway(gw)

        val serialized = makeReadable(dhcp.build())
        serialized.contains(makeReadable(opt121.build())) shouldBe true
        serialized.startsWith(classOf[Dhcp].getName) shouldBe true
    }

}
