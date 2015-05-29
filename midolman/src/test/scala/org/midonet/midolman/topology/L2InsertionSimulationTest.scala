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

import com.typesafe.config.{Config, ConfigValueFactory}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.concurrent.Eventually._
import org.scalatest.time.{Seconds, Span}


import org.midonet.cluster.data.storage.{UpdateOp, DeleteOp, CreateOp, Storage}
import org.midonet.cluster.models.L2InsertionTranslation._
import org.midonet.cluster.models.Topology.{L2Insertion, Port, Network}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.simulation.{Bridge, Chain => SimChain}
import org.midonet.midolman.topology.devices.{Port => SimPort}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.Ethernet
import org.midonet.util.concurrent.FutureOps

@RunWith(classOf[JUnitRunner])
class L2InsertionSimulationTest extends MidolmanSpec with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var store: Storage = _

    registerActors(
        VirtualTopologyActor -> (() => new VirtualTopologyActor()))

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
    }

    protected override def fillConfig(config: Config) = {
        super.fillConfig(config).withValue("zookeeper.use_new_stack",
                                           ConfigValueFactory.fromAnyRef(true))
    }

    implicit def toFutureOps[U](future: Future[U]): FutureOps[U] = {
        new FutureOps(future)
    }

    def tryGet(thunk: () => Unit): Unit = {
        try {
            thunk()
        } catch {
            case e: NotYetException => e.waitFor.await(3 seconds)
        }
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
                    eth addr srcMac -> dstMac vlan (v)
                } << {
                    ip4 addr "10.0.0.10" --> "10.0.0.11"
                } << {
                    udp ports 53 ---> 54
                } <<
                payload(UUID.randomUUID().toString)
        }
    }

    def checkPacket(when: String, then: String, frame: Ethernet,
                    srcPortId: UUID, dstPortId: UUID,
                    expectedVlan: Option[Short] = None) = {
        When(when)
        val packetContext = packetContextFor(frame, srcPortId)
        val result = simulate(packetContext)

        Then(then)
        result should be(toPort(dstPortId)())
        expectedVlan match {
            case None =>
                result._2.wcmatch.getVlanIds.size() should be(0)
            case Some(vlan) =>
                //result should be(hasPushVlan())
                result._2.wcmatch.getVlanIds.get(0) should be(vlan)
        }
    }

    def checkInsertionPath(srcPortId: UUID, dstPortId: UUID,
                   srcMac: String, dstMac: String,
                   insertions: Seq[L2Insertion], forward: Boolean) = {
        var prevPortId: UUID = srcPortId
        var prevVlan: Option[Short] = None
        var nextVlan: Option[Short] = None
        var i: Int = 0
        insertions.foreach(
            x =>  {
                forward match {
                    case true =>
                        x.getMac should be(srcMac)
                        x.getPort should be(srcPortId.asProto)
                    case false =>
                        x.getMac should be(dstMac)
                        x.getPort should be(dstPortId.asProto)
                }
                nextVlan = x.getVlan match {
                    case 0 => None
                    case v =>
                        forward match {
                            case true => Some((v | 1 << 13).toShort)
                            case false => Some((v | 2 << 13).toShort)
                        }
                }
                checkPacket("The packet ingresses the previous port",
                            "It's redirected out srv port-" + i,
                            packet(srcMac, dstMac, prevVlan),
                            prevPortId, x.getSrvPort, nextVlan)
                i = i+1
                prevVlan = nextVlan
                prevPortId = x.getSrvPort
            })
        checkPacket("The packet ingresses the last srv port",
                    "It finally makes its way out the destination port",
                    packet(srcMac, dstMac, prevVlan),
                    prevPortId, dstPortId)
    }

    def checkRoundTrip(srcPortId: UUID, dstPortId: UUID,
                       srcMac: String, dstMac: String,
                       insertions: Seq[L2Insertion]) = {
        // First we check the forward path
        checkInsertionPath(srcPortId, dstPortId, srcMac, dstMac,
                           insertions, true)
        // Then we check the return path
        checkInsertionPath(dstPortId, srcPortId, dstMac, srcMac,
                           insertions, false)
    }

    def waitForPortChain(portId: UUID, size: Int, inbound: Boolean) = {
        eventually (timeout(Span(2, Seconds))) {
            val port = VirtualTopology.tryGet[SimPort](portId)
            val chain = inbound match {
                case true =>
                    VirtualTopology.tryGet[SimChain](port.inboundChains(0))
                case false =>
                    VirtualTopology.tryGet[SimChain](port.outboundChains(0))
            }
            chain.getRules.size should be(size)
        }
    }

    feature("Test redirect without vlans") {
        scenario("Test") {
            val mac1 = "02:00:00:00:ee:00"
            val mac2 = "02:00:00:00:ee:11"
            val mac3 = "02:00:00:00:ee:22"
            val mac4 = "02:00:00:00:ee:33"

            val host = createHost()
            store.create(host)

            val vmBridge1 = createBridge(name = Some("vmBridge1"),
                                         adminStateUp = true)
            store.create(vmBridge1)
            val vmBridge2 = createBridge(name = Some("vmBridge2"),
                                         adminStateUp = true)
            store.create(vmBridge2)
            val srvBridge = createBridge(name = Some("srvBridge"),
                                         adminStateUp = true)
            store.create(srvBridge)

            def bridgePort(interface: String, bridge: Network) = {
                val port = createBridgePort(
                    hostId = Some(host.getId),
                    interfaceName = Some(interface),
                    adminStateUp = true,
                    bridgeId = Some(bridge.getId))
                store.create(port)
                port
            }
            // Two VMs on bridge1
            val vm1Port = bridgePort("vm1_if", vmBridge1)
            val vm2Port = bridgePort("vm2_if", vmBridge1)
            // Two VMs on bridge2
            val vm3Port = bridgePort("vm3_if", vmBridge2)
            val vm4Port = bridgePort("vm4_if", vmBridge2)
            // Two services on the service bridge
            val srv1Port = bridgePort("srv1_if", srvBridge)
            val srv2Port = bridgePort("srv2_if", srvBridge)

            // Load the cache to avoid NotYetException at simulation time
            for (port <- List(vm1Port, vm2Port, vm3Port, vm4Port,
                              srv1Port, srv2Port))
                tryGet(() => VirtualTopology.tryGet[SimPort](port.getId))
            for (bridge <- List(vmBridge1, vmBridge2, srvBridge))
                tryGet(() => VirtualTopology.tryGet[Bridge](bridge.getId))

            // Set all the ports to "Active"
            for (port <- List(vm1Port, vm2Port, vm3Port, vm4Port,
                              srv1Port, srv2Port)) {
                VirtualTopology.tryGet[SimPort](port.getId)._active = true
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
                .setPort(vm1Port.getId)
                .setSrvPort(srv1Port.getId)
                .setPosition(1)
                .setVlan(10)
                .build
            updateInsertions(store, CreateOp(vm1ins1)) should be(true)

            waitForPortChain(vm1Port.getId, 3, true)
            waitForPortChain(vm1Port.getId, 2, false)
            waitForPortChain(srv1Port.getId, 2, true)

            // Now check the insertion on vm1
            checkRoundTrip(vm1Port.getId, vm2Port.getId,
                           mac1, mac2, Seq(vm1ins1))

            // Now add another insertion vm1 -> srv2
            val vm1ins2 = L2Insertion.newBuilder
                .setId(UUID.randomUUID().asProto)
                .setMac(mac1)
                .setPort(vm1Port.getId)
                .setSrvPort(srv2Port.getId)
                .setPosition(2)
                .setVlan(20)
                .build
            updateInsertions(store, CreateOp(vm1ins2)) should be(true)
            waitForPortChain(vm1Port.getId, 4, true)
            waitForPortChain(vm1Port.getId, 3, false)
            waitForPortChain(srv2Port.getId, 2, true)

            // Now check the two insertions on vm1
            checkRoundTrip(vm1Port.getId, vm2Port.getId,
                           mac1, mac2, Seq(vm1ins1, vm1ins2))

            // Now modify the insertion vm1->srv1 to use vlan 30.
            vm1ins1 = vm1ins1.toBuilder.setVlan(30).build
            updateInsertions(store, UpdateOp(vm1ins1)) should be(true)
            eventually (timeout(Span(2, Seconds))) {
                val port = VirtualTopology.tryGet[SimPort](vm1ins1.getPort)
                val chain = VirtualTopology
                    .tryGet[SimChain](port.inboundChains(0))
                chain.getRules.get(3).pushVlan should be(30 | 1 << 13)
            }
            // Now check the two insertions on vm1
            checkRoundTrip(vm1Port.getId, vm2Port.getId,
                           mac1, mac2, Seq(vm1ins1, vm1ins2))

            // Now modify the insertion vm1->srv1 to NOT use vlan tags.
            vm1ins1 = vm1ins1.toBuilder.setVlan(0).build
            updateInsertions(store, UpdateOp(vm1ins1)) should be(true)
            eventually (timeout(Span(2, Seconds))) {
                val port = VirtualTopology.tryGet[SimPort](vm1ins1.getPort)
                val chain = VirtualTopology
                    .tryGet[SimChain](port.inboundChains(0))
                chain.getRules.get(3).pushVlan should be(0)
            }
            // Now check the two insertions on vm1
            checkRoundTrip(vm1Port.getId, vm2Port.getId,
                           mac1, mac2, Seq(vm1ins1, vm1ins2))

            // Now add an insertion vm3 -> srv1, using fail_open=true
            val vm3ins1 = L2Insertion.newBuilder
                .setId(UUID.randomUUID().asProto)
                .setMac(mac3)
                .setPort(vm3Port.getId)
                .setSrvPort(srv1Port.getId)
                .setPosition(1)
                .setVlan(10)
                .setFailOpen(true)
                .build
            updateInsertions(store, CreateOp(vm3ins1)) should be(true)

            waitForPortChain(vm3Port.getId, 3, true)
            waitForPortChain(vm3Port.getId, 2, false)
            // srv1 now has 4 jump rules...
            waitForPortChain(srv1Port.getId, 4, true)

            for (port <- List(vm1Port, vm2Port, vm3Port, vm4Port,
                              srv1Port, srv2Port)) {
                VirtualTopology.tryGet[SimPort](port.getId)._active = true
            }

            // Now check the insertion on vm3
            checkRoundTrip(vm3Port.getId, vm4Port.getId,
                           mac3, mac4, Seq(vm3ins1))

            // Now set srvPort to down. Verify traffic still flows thanks to
            // fail_open=true.
            VirtualTopology.tryGet[SimPort](srv1Port.getId)._active = false
            checkRoundTrip(vm3Port.getId, vm4Port.getId,
                           mac3, mac4, Seq.empty[L2Insertion])

            // Now delete the insertion vm1 -> srv1 (keep vm1 -> srv2)
            updateInsertions(store,
                             DeleteOp(classOf[L2Insertion],
                                      vm1ins1.getId)) should be(true)

            waitForPortChain(vm1Port.getId, 3, true)
            waitForPortChain(vm1Port.getId, 2, false)
            waitForPortChain(srv1Port.getId, 2, true)

            // Now check the remaining insertion on vm1
            checkRoundTrip(vm1Port.getId, vm2Port.getId, mac1, mac2,
                           Seq(vm1ins2))

            // Finally, delete the 2 remaining insertions.
            updateInsertions(store,
                             DeleteOp(classOf[L2Insertion],
                                      vm1ins2.getId)) should be(true)
            updateInsertions(store,
                             DeleteOp(classOf[L2Insertion],
                                      vm3ins1.getId)) should be(true)
            waitForPortChain(vm1Port.getId, 0, true)
            waitForPortChain(vm1Port.getId, 0, false)
            waitForPortChain(vm3Port.getId, 0, true)
            waitForPortChain(vm3Port.getId, 0, false)
            waitForPortChain(srv1Port.getId, 0, true)
            waitForPortChain(srv2Port.getId, 0, true)
        }
    }
}
