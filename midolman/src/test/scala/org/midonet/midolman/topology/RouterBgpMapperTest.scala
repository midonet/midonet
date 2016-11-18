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

package org.midonet.midolman.topology

import java.util.UUID
import java.util.concurrent.TimeoutException

import scala.collection.mutable
import scala.concurrent.duration.{Duration, DurationInt}

import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.models.Topology.{BgpPeer, Port, Router}
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.{IPv4Addr, IPv4Subnet}
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class RouterBgpMapperTest extends MidolmanSpec with TopologyBuilder
                                   with TopologyMatchers with Eventually {
    private var vt: VirtualTopology = _
    private val timeout: Duration = 1 second

    override protected def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
    }

    feature("RouterBgpMapper") {
        scenario("Publishes PortBgpInfo for existing topology.") {
            val cidrs = Seq("10.0.1.1/24", "10.0.2.1/24", "10.1.0.1/16")
            val ports = createRouterWithPorts(cidrs)
            val routerId = ports.head.getRouterId.asJava
            createBgpPeer(routerId, ports(1).getPortSubnet(0).getAddress)
            createBgpPeer(routerId, ports(2).getPortSubnet(0).getAddress)

            val obs = createMapperAndObserver(routerId)
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0).toSet shouldBe
                portBgpInfoSet(ports.tail, cidrs.tail)
        }

        scenario("Publishes updates for port addition and deletion") {
            val cidrs = Seq("10.0.1.1/24", "10.1.0.1/16", "11.0.0.1/8")

            // Create router with two ports and BGPs for all three addresses.
            val ports = mutable.ListBuffer[Port]()
            ports ++= createRouterWithPorts(cidrs.take(2))
            val routerId = ports.head.getRouterId.asJava
            for (cidr <- cidrs)
                createBgpPeer(routerId, cidr)

            val obs = createMapperAndObserver(routerId)
            obs.awaitOnNext(1, timeout)
            obs.getOnNextEvents.get(0).toSet shouldBe
                portBgpInfoSet(ports, cidrs.take(2))

            // Add an additional port.
            val cidr2Subnet = IPv4Subnet.fromCidr(cidrs(2))
            ports += createRouterPort(routerId = Some(routerId),
                                      portAddress = cidr2Subnet.getAddress,
                                      portSubnet = cidr2Subnet)
            vt.store.create(ports.last)
            obs.awaitOnNext(2, timeout)
            obs.getOnNextEvents.get(1).toSet shouldBe
                portBgpInfoSet(ports, cidrs)

            // Delete a port.
            vt.store.delete(classOf[Port], ports.head.getId)
            ports.remove(0)
            obs.awaitOnNext(3, timeout)
            obs.getOnNextEvents.get(2).toSet shouldBe
                portBgpInfoSet(ports, cidrs.tail)

            // Add the port back.
            val cidr0Subnet = IPv4Subnet.fromCidr(cidrs.head)
            ports += createRouterPort(routerId = Some(routerId),
                                      portAddress = cidr0Subnet.getAddress,
                                      portSubnet = cidr0Subnet)
            vt.store.create(ports.last)
            obs.awaitOnNext(4, timeout)
            obs.getOnNextEvents.get(3).toSet shouldBe
                portBgpInfoSet(ports, cidrs.tail :+ cidrs.head)

            // Create a port with no BGP peer.
            vt.store.create(createRouterPort(
                routerId = Some(routerId), portAddress = IPv4Addr("10.0.4.1"),
                portSubnet = new IPv4Subnet("10.0.4.1", 24)))
            intercept[TimeoutException] {
                obs.awaitOnNext(5, timeout)
            }
        }

        scenario("Completes on router deletion") {
            val cidrs = Seq("10.0.1.0/24", "10.1.0.0/16", "11.0.0.0/8")
            val ports = createRouterWithPorts(cidrs)
            val routerId = ports.head.getRouterId.asJava
            val obs = createMapperAndObserver(routerId)
            obs.awaitOnNext(1, timeout)

            vt.store.delete(classOf[Router], routerId)
            obs.awaitCompletion(timeout)
        }
    }


    private def createRouterWithPorts(cidrs: Seq[String]): Seq[Port] = {
        val r = createRouter()
        vt.store.create(r)
        for (cidr <- cidrs) yield {
            val subnet = IPv4Subnet.fromCidr(cidr)
            val p = createRouterPort(routerId = Some(r.getId.asJava),
                                     portAddress = subnet.getAddress,
                                     portSubnet = subnet)
            vt.store.create(p)
            p
        }
    }

    private def portBgpInfo(p: Port, cidr: String): PortBgpInfo = {
        PortBgpInfo(p.getId.asJava, p.getPortMac, cidr,
                    IPv4Subnet.fromCidr(cidr).getAddress.toString)
    }

    private def portBgpInfoSet(ports: Iterable[Port], cidrs: Iterable[String])
    : Set[PortBgpInfo] = {
        ports.zip(cidrs).map { case (p, cidr) => portBgpInfo(p, cidr) }.toSet
    }

    private def createBgpPeer(routerId: UUID, cidr: String): BgpPeer = {
        val addr = IPv4Subnet.fromCidr(cidr).getAddress
        val peer = createBgpPeer(address = Some(addr),
                                 routerId = Some(routerId))
        vt.store.create(peer)
        peer
    }

    private def createMapperAndObserver(routerId: UUID)
    : TestAwaitableObserver[Seq[PortBgpInfo]] = {
        val mapper = new RouterBgpMapper(routerId, vt)
        val obs = new TestAwaitableObserver[Seq[PortBgpInfo]]
        mapper.portBgpInfoObservable.subscribe(obs)
        obs
    }
}
