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

import scala.concurrent.Future
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually._
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.{Seconds, Span}

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology.{L2Insertion, Network, Port}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.services.c3po.translators.L2InsertionTranslation._
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.rules.L2TransformRule
import org.midonet.midolman.simulation.{Chain => SimChain, Port => SimPort}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.Ethernet
import org.midonet.util.concurrent.FutureOps
import org.midonet.util.reactivex._

@RunWith(classOf[JUnitRunner])
class L2InsertionSimulationTest extends MidolmanSpec with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private var stateStore: StateStorage = _

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        stateStore = injector.getInstance(classOf[MidonetBackend]).stateStore
    }

    implicit def toFutureOps[U](future: Future[U]): FutureOps[U] = {
        new FutureOps(future)
    }

    def packet(srcMac: String, dstMac: String, vlan: Option[Short] = None) = {
        import org.midonet.packets.util.PacketBuilder._

        vlan match {
            case None =>
                {
                    eth addr srcMac -> dstMac
                } << {
                    ip4 addr "10.0.0.10" --> "10.0.0.11"
                } << {
                    udp ports 53 ---> 54
                } <<
                payload(UUID.randomUUID().toString)
            case Some(v) =>
                {
                    eth addr srcMac -> dstMac vlan v
                } << {
                    ip4 addr "10.0.0.10" --> "10.0.0.11"
                } << {
                    udp ports 53 ---> 54
                } <<
                payload(UUID.randomUUID().toString)
        }
    }

    def checkPacket(when: String, `then`: String, frame: Ethernet,
                    srcPortId: UUID, dstPortId: UUID,
                    expectedVlan: Option[Short] = None) = {
        When(when)
        val packetContext = packetContextFor(frame, srcPortId)
        val result = simulate(packetContext)

        Then(`then`)
        result should be(toPort(dstPortId)())
        expectedVlan match {
            case None =>
                result._2.wcmatch.getVlanIds.size() should be(0)
            case Some(vlan) =>
                result._2.wcmatch.getVlanIds.get(0) should be(vlan)
        }
    }

    def checkInsertionPath(srcPortId: UUID, dstPortId: UUID,
                   srcMac: String, dstMac: String,
                   srcInsertions: Seq[L2Insertion],
                   dstInsertions: Seq[L2Insertion]) = {
        var prevPortId: UUID = srcPortId
        var prevVlan: Option[Short] = None
        var nextVlan: Option[Short] = None
        var i: Int = 0
        def checkInsertions(insertions: Seq[L2Insertion], inbound: Boolean) = {
            insertions.foreach(
                x =>  {
                    inbound match {
                        case true =>
                            x.getMac should be(srcMac)
                            x.getPortId should be(srcPortId.asProto)
                        case false =>
                            x.getMac should be(dstMac)
                            x.getPortId should be(dstPortId.asProto)
                    }
                    nextVlan = x.getVlan match {
                        case 0 => None
                        case v =>
                            inbound match {
                                case true => Some((v | 1 << 13).toShort)
                                case false => Some((v | 2 << 13).toShort)
                            }
                    }
                    checkPacket("The packet ingresses the previous port",
                                "It's redirected out srv port-" + i,
                                packet(srcMac, dstMac, prevVlan),
                                prevPortId, x.getSrvPortId, nextVlan)
                    i = i+1
                    prevVlan = nextVlan
                    prevPortId = x.getSrvPortId
                })
        }
        checkInsertions(srcInsertions, true)
        checkInsertions(dstInsertions, false)
        checkPacket("The packet ingresses the last srv port",
                    "It finally makes its way out the destination port",
                    packet(srcMac, dstMac, prevVlan),
                    prevPortId, dstPortId)
    }

    def checkRoundTrip(srcPortId: UUID, dstPortId: UUID,
                       srcMac: String, dstMac: String,
                       srcInsertions: Seq[L2Insertion],
                       dstInsertions: Seq[L2Insertion] = Seq.empty[L2Insertion]
                          ) = {
        // First we check the forward path
        log.info("Checking forward path")
        checkInsertionPath(srcPortId, dstPortId, srcMac, dstMac,
                           srcInsertions, dstInsertions)
        // Then we check the return path
        log.info("Checking return path")
        checkInsertionPath(dstPortId, srcPortId, dstMac, srcMac,
                           dstInsertions, srcInsertions)
    }

    feature("Test redirect without vlans") {
        scenario("Test") {
            val mac1 = "02:00:00:00:ee:00"
            val mac2 = "02:00:00:00:ee:11"
            val mac3 = "02:00:00:00:ee:22"
            val mac4 = "02:00:00:00:ee:33"

            store create createHost(id = hostId)

            val vmBridge1 = createBridge(name = Some("vmBridge1"),
                                         adminStateUp = true)
            store.create(vmBridge1)
            val vmBridge2 = createBridge(name = Some("vmBridge2"),
                                         adminStateUp = true)
            store.create(vmBridge2)
            val srvBridge = createBridge(name = Some("srvBridge"),
                                         adminStateUp = true)
            store.create(srvBridge)

            def bridgePort(id: UUID, interface: String, bridge: Network) = {
                val port = createBridgePort(
                    id=id,
                    hostId = Some(hostId),
                    interfaceName = Some(interface),
                    adminStateUp = true,
                    bridgeId = Some(bridge.getId))
                store.create(port)
                port
            }
            // Two VMs on bridge1
            val vm1Port = bridgePort(new UUID(0, 1), "vm1_if", vmBridge1)
            val vm2Port = bridgePort(new UUID(0, 2), "vm2_if", vmBridge1)
            // Two VMs on bridge2
            val vm3Port = bridgePort(new UUID(0, 3), "vm3_if", vmBridge2)
            val vm4Port = bridgePort(new UUID(0, 4), "vm4_if", vmBridge2)
            // Two services on the service bridge
            val srv1Port = bridgePort(new UUID(1, 1), "srv1_if", srvBridge)
            val srv2Port = bridgePort(new UUID(1, 2), "srv2_if", srvBridge)

            // Load the cache to avoid NotYetException at simulation time
            fetchPorts(vm1Port.getId, vm2Port.getId,
                       vm3Port.getId, vm4Port.getId,
                       srv1Port.getId, srv2Port.getId)
            fetchBridges(vmBridge1.getId, vmBridge2.getId, srvBridge.getId)

            // Set all the ports to "Active"
            val hostIdString = fromProto(hostId).toString
            for (port <- List(vm1Port, vm2Port, vm3Port, vm4Port,
                              srv1Port, srv2Port)) {
                stateStore.addValue(classOf[Port], port.getId, ActiveKey,
                                    hostIdString).await(3 seconds)
            }

            // Before adding insertions, send forward and return packets to
            // seed the MAC tables. This avoids bridge flood actions and
            // allows 'checkPacket' to always check for a single output port.
            simulate(packetContextFor(packet(mac1, mac2), vm1Port.getId))
            simulate(packetContextFor(packet(mac2, mac1), vm2Port.getId))
            simulate(packetContextFor(packet(mac3, mac4), vm3Port.getId))
            simulate(packetContextFor(packet(mac4, mac3), vm4Port.getId))

            // Now add an insertion vm1 -> srv1
            var vm1ins1 = L2Insertion.newBuilder
                .setId(UUID.randomUUID().asProto)
                .setMac(mac1)
                .setPortId(vm1Port.getId)
                .setSrvPortId(srv1Port.getId)
                .setPosition(1)
                .setVlan(10)
                .build
            translateInsertionCreate(store, vm1ins1)
            fetchPorts(srv1Port.getId)

            // Now check the insertion on vm1
            checkRoundTrip(vm1Port.getId, vm2Port.getId,
                           mac1, mac2, Seq(vm1ins1))

            // Now add another insertion vm1 -> srv2
            val vm1ins2 = L2Insertion.newBuilder
                .setId(UUID.randomUUID().asProto)
                .setMac(mac1)
                .setPortId(vm1Port.getId)
                .setSrvPortId(srv2Port.getId)
                .setPosition(2)
                .setVlan(20)
                .build
            translateInsertionCreate(store, vm1ins2)
            fetchPorts(srv2Port.getId)

            // Now check the two insertions on vm1
            checkRoundTrip(vm1Port.getId, vm2Port.getId,
                           mac1, mac2, Seq(vm1ins1, vm1ins2))

            // Now add an insertion on vm2 (same bridge as vm1)
            val vm2ins1 = L2Insertion.newBuilder
                .setId(UUID.randomUUID().asProto)
                .setMac(mac2)
                .setPortId(vm2Port.getId)
                .setSrvPortId(srv1Port.getId)
                .setPosition(1)
                .setVlan(10)
                .build
            translateInsertionCreate(store, vm2ins1)

            // Now check the concurrent insertions on vm1 and vm2 (same bridge)
            checkRoundTrip(vm1Port.getId, vm2Port.getId,
                           mac1, mac2, Seq(vm1ins1, vm1ins2), Seq(vm2ins1))
            // Now delete the insertion on vm2
            translateInsertionDelete(store, vm2ins1.getId)

            // Now modify the insertion vm1->srv1 to use vlan 30.
            vm1ins1 = vm1ins1.toBuilder.setVlan(30).build
            translateInsertionUpdate(store, vm1ins1)
            eventually (timeout(Span(2, Seconds))) {
                val port = VirtualTopology.tryGet[SimPort](vm1ins1.getPortId)
                val chain = VirtualTopology
                    .tryGet[SimChain](port.inboundFilters.get(0))
                chain.rules.get(3) match {
                    case r: L2TransformRule => r.pushVlan should be(30 | 1 << 13)
                    case _ => fail("wrong rule type")
                }
            }
            // Now check the two insertions on vm1
            checkRoundTrip(vm1Port.getId, vm2Port.getId,
                           mac1, mac2, Seq(vm1ins1, vm1ins2))

            // Now modify the insertion vm1->srv1 to NOT use vlan tags.
            vm1ins1 = vm1ins1.toBuilder.setVlan(0).build
            translateInsertionUpdate(store, vm1ins1)
            eventually (timeout(Span(2, Seconds))) {
                val port = VirtualTopology.tryGet[SimPort](vm1ins1.getPortId)
                val chain = VirtualTopology
                    .tryGet[SimChain](port.inboundFilters.get(0))
                chain.rules.get(3) match {
                    case r: L2TransformRule => r.pushVlan shouldBe 0
                    case _ => fail("wrong rule type")
                }
            }
            // Now check the two insertions on vm1
            checkRoundTrip(vm1Port.getId, vm2Port.getId,
                           mac1, mac2, Seq(vm1ins1, vm1ins2))

            // Now add an insertion vm3 -> srv1, using fail_open=true
            val vm3ins1 = L2Insertion.newBuilder
                .setId(UUID.randomUUID().asProto)
                .setMac(mac3)
                .setPortId(vm3Port.getId)
                .setSrvPortId(srv1Port.getId)
                .setPosition(1)
                .setVlan(10)
                .setFailOpen(true)
                .build
            translateInsertionCreate(store, vm3ins1)

            // Now check the insertion on vm3
            checkRoundTrip(vm3Port.getId, vm4Port.getId,
                           mac3, mac4, Seq(vm3ins1))

            // Now set srv1Port to down. Verify traffic still flows thanks to
            // fail_open=true.
            stateStore.removeValue(classOf[Port], srv1Port.getId, ActiveKey,
                                hostIdString).await(3 seconds)

            checkRoundTrip(vm3Port.getId, vm4Port.getId,
                           mac3, mac4, Seq.empty[L2Insertion])

            // Now delete the insertion vm1 -> srv1 (keep vm1 -> srv2)
            translateInsertionDelete(store, vm1ins1.getId)

            // Now check the remaining insertion on vm1
            checkRoundTrip(vm1Port.getId, vm2Port.getId, mac1, mac2,
                           Seq(vm1ins2))

            // Finally, delete the 2 remaining insertions.
            translateInsertionDelete(store, vm1ins2.getId)
            translateInsertionDelete(store, vm3ins1.getId)
        }
    }
}
