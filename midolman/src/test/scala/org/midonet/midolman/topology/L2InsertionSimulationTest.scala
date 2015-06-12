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


import org.midonet.cluster.data.storage.{DeleteOp, CreateOp, Storage}
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
                    srcPort: Port, dstPort: Port,
                    expectedVlan: Option[Int] = None) = {
        When(when)
        val packetContext = packetContextFor(frame, srcPort.getId)
        val result = simulate(packetContext)

        Then(then)
        result should be(toPort(dstPort.getId)())
        expectedVlan match {
            case None =>
            case Some(vlan) =>
                //result should be(hasPushVlan())
                result._2.wcmatch.getVlanIds.get(0) should be(vlan)
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
            val ins1id = UUID.randomUUID().asProto
            updateInsertions(
                store, CreateOp(L2Insertion.newBuilder
                             .setId(ins1id)
                             .setMac(mac1)
                             .setPort(vm1Port.getId)
                             .setSrvPort(srv1Port.getId)
                             .setPosition(1)
                             .setVlan(10)
                             .build)) should be(true)

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
            waitForPortChain(vm1Port.getId, 2, true)
            waitForPortChain(vm1Port.getId, 2, false)
            waitForPortChain(srv1Port.getId, 2, true)

            // Now check the insertion
            checkPacket("A forward packet ingresses vm1's port",
                        "It's redirected out srv1's port with vlan 10",
                        packet(mac1, mac2), vm1Port, srv1Port, Some(10))
            checkPacket("A forward packet ingresses srv1's port with vlan 10",
                        "It egresses vm2's port without vlan",
                        packet(mac1, mac2, Some(10)), srv1Port, vm2Port)
            checkPacket("A return packet ingresses vm2's port",
                        "It's redirected out srv1's port with vlan 10",
                        packet(mac2, mac1), vm2Port, srv1Port, Some(10))
            checkPacket("A return packet ingresses srv1's port with vlan 10",
                        "It egresses vm1's port without vlan",
                        packet(mac2, mac1, Some(10)), srv1Port, vm1Port)

            // Now add another insertion vm1 -> srv2
            updateInsertions(
                store, CreateOp(
                    L2Insertion.newBuilder
                        .setId(UUID.randomUUID().asProto)
                        .setMac(mac1)
                        .setPort(vm1Port.getId)
                        .setSrvPort(srv2Port.getId)
                        .setPosition(2)
                        .setVlan(20)
                        .build)) should be(true)
            waitForPortChain(vm1Port.getId, 3, true)
            waitForPortChain(vm1Port.getId, 3, false)
            waitForPortChain(srv2Port.getId, 2, true)

            checkPacket("A forward packet ingresses vm1's port",
                        "It's redirected out srv1's port with vlan 10",
                        packet(mac1, mac2), vm1Port, srv1Port, Some(10))
            checkPacket("A forward packet ingresses srv1's port with vlan 10",
                        "It's redirected out srv2's port with vlan 20",
                        packet(mac1, mac2, Some(10)), srv1Port, srv2Port, Some(20))
            checkPacket("A forward packet ingresses srv2's port with vlan 20",
                        "It egresses vm2's port without vlan",
                        packet(mac1, mac2, Some(20)), srv2Port, vm2Port)
            checkPacket("A return packet ingresses vm2's port",
                        "It's redirected out srv1's port with vlan 10",
                        packet(mac2, mac1), vm2Port, srv1Port, Some(10))
            checkPacket("A return packet ingresses srv1's port with vlan 10",
                        "It's redirected out srv2's port with vlan 20",
                        packet(mac2, mac1, Some(10)), srv1Port, srv2Port, Some(20))
            checkPacket("A return packet ingresses srv2's port with vlan 20",
                        "It egresses vm1's port without vlan",
                        packet(mac2, mac1, Some(20)), srv2Port, vm1Port)

            // Now add an insertion vm3 -> srv1
            updateInsertions(
                store, CreateOp(
                    L2Insertion.newBuilder
                        .setId(UUID.randomUUID().asProto)
                        .setMac(mac3)
                        .setPort(vm3Port.getId)
                        .setSrvPort(srv1Port.getId)
                        .setPosition(1)
                        .setVlan(10)
                        .build)) should be(true)

            waitForPortChain(vm3Port.getId, 2, true)
            waitForPortChain(vm3Port.getId, 2, false)
            // srv1 now has 4 jump rules...
            waitForPortChain(srv1Port.getId, 4, true)

            checkPacket("A forward packet ingresses vm3's port",
                        "It's redirected out srv1's port with vlan 10",
                        packet(mac3, mac4), vm3Port, srv1Port, Some(10))
            checkPacket("A forward packet ingresses srv1's port with vlan 10",
                        "It egresses vm4's port without vlan",
                        packet(mac3, mac4, Some(10)), srv1Port, vm4Port)
            checkPacket("A return packet ingresses vm4's port",
                        "It's redirected out srv1's port with vlan 10",
                        packet(mac4, mac3), vm4Port, srv1Port, Some(10))
            checkPacket("A return packet ingresses srv1's port with vlan 10",
                        "It egresses vm3's port without vlan",
                        packet(mac4, mac3, Some(10)), srv1Port, vm3Port)

            // Now delete the insertion vm1 -> srv1 (keep vm1 -> srv2)
            updateInsertions(
                store, DeleteOp(classOf[L2Insertion], ins1id)) should be(true)

            waitForPortChain(vm1Port.getId, 2, true)
            waitForPortChain(vm1Port.getId, 2, false)
            waitForPortChain(srv1Port.getId, 2, true)

            // Now check the insertion
            checkPacket("A forward packet ingresses vm1's port",
                        "It's redirected out srv2's port with vlan 20",
                        packet(mac1, mac2), vm1Port, srv2Port, Some(20))
            checkPacket("A forward packet ingresses srv2's port with vlan 20",
                        "It egresses vm2's port without vlan",
                        packet(mac1, mac2, Some(20)), srv2Port, vm2Port)
            checkPacket("A return packet ingresses vm2's port",
                        "It's redirected out srv2's port with vlan 20",
                        packet(mac2, mac1), vm2Port, srv2Port, Some(20))
            checkPacket("A return packet ingresses srv2's port with vlan 20",
                        "It egresses vm1's port without vlan",
                        packet(mac2, mac1, Some(20)), srv2Port, vm1Port)

        }
    }
}
