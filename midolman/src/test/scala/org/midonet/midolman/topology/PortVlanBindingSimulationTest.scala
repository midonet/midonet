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
import org.scalatest.concurrent.Eventually._
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.{Seconds, Span}

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.PortVlanBindingTranslation._
import org.midonet.cluster.models.Topology.{PortVlanBinding, Network, Port}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.simulation.{Bridge, Chain => SimChain, Port => SimPort}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.Ethernet
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.concurrent.FutureOps
import org.midonet.util.reactivex._

@RunWith(classOf[JUnitRunner])
class PortVlanBindingSimulationTest extends MidolmanSpec with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var store: Storage = _
    private var stateStore: StateStorage = _

    registerActors(
        VirtualTopologyActor -> (() => new VirtualTopologyActor()))

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        stateStore = injector.getInstance(classOf[MidonetBackend]).stateStore
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
                    expectedVlan: Option[Short] = None)
                   (expectedTags: FlowTag*) = {
        When(when)
        val packetContext = packetContextFor(frame, srcPortId)
        val result = simulate(packetContext)

        Then(then)
        result should be(toPort(dstPortId)(expectedTags : _*))
        expectedVlan match {
            case None =>
                result._2.wcmatch.getVlanIds.size() should be(0)
            case Some(vlan) =>
                //result should be(hasPushVlan())
                result._2.wcmatch.getVlanIds.get(0) should be(vlan)
        }
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
            chain.rules.size should be(size)
        }
    }

    feature("Test PortVlanBinding") {
        scenario("Two trunk ports") {
            val b1p1mac= "02:00:00:00:ee:00"
            val b1p2mac = "02:00:00:00:ee:11"
            val b2p1mac = "02:00:00:00:ee:22"
            val b2p2mac = "02:00:00:00:ee:33"
            val b3p1mac = "02:00:00:00:ee:44"
            val b3p2mac = "02:00:00:00:ee:55"

            val host = createHost()
            store.create(host)

            val vmBridge1 = createBridge(name = Some("vmBridge1"),
                                         adminStateUp = true)
            store.create(vmBridge1)
            val vmBridge2 = createBridge(name = Some("vmBridge2"),
                                         adminStateUp = true)
            store.create(vmBridge2)
            val vmBridge3 = createBridge(name = Some("vmBridge3"),
                                         adminStateUp = true)
            store.create(vmBridge3)

            def bridgePort(bridge: Network,
                           interface: Option[String] = None) = {
                val port = createBridgePort(
                    hostId = Some(host.getId),
                    interfaceName = interface,
                    adminStateUp = true,
                    bridgeId = Some(bridge.getId))
                store.create(port)
                port
            }
            // Two VMs on bridge1. The ports are external, bound to interfaces.
            val b1p1 = bridgePort(vmBridge1, Some("vm1_if"))
            val b1p2 = bridgePort(vmBridge1, Some("vm2_if"))
            // Two ports on bridge2 are vlan ports bound to ports on bridge1
            val b2p1 = bridgePort(vmBridge2)
            val b2p2 = bridgePort(vmBridge2)
            // Two ports on bridge3 are vlan ports bound to ports on bridge1
            val b3p1 = bridgePort(vmBridge3)
            val b3p2 = bridgePort(vmBridge3)

            // Load the cache to avoid NotYetException at simulation time
            for (port <- List(b1p1, b1p2, b2p1, b2p2, b3p1, b3p2))
                tryGet(() => VirtualTopology.tryGet[SimPort](port.getId))
            for (bridge <- List(vmBridge1, vmBridge2, vmBridge3))
                tryGet(() => VirtualTopology.tryGet[Bridge](bridge.getId))

            // Set the external ports to "Active"
            val hostIdString = fromProto(host.getId).toString
            for (port <- List(b1p1, b1p2)) {
                stateStore.addValue(classOf[Port], port.getId, HostsKey,
                                    hostIdString).await(3 seconds)
            }

            // Send forward and return packets to
            // seed the MAC tables. This avoids bridge flood actions and
            // allows 'checkPacket' to always check for a single output port.
            simulate(packetContextFor(packet(b1p1mac, b1p2mac), b1p1.getId))
            simulate(packetContextFor(packet(b1p2mac, b1p1mac), b1p2.getId))
            simulate(packetContextFor(packet(b2p1mac, b2p2mac), b2p1.getId))
            simulate(packetContextFor(packet(b2p2mac, b2p1mac), b2p2.getId))
            simulate(packetContextFor(packet(b3p1mac, b3p2mac), b3p1.getId))
            simulate(packetContextFor(packet(b3p2mac, b3p1mac), b3p2.getId))

            // Now add PortVlanBindings
            val bind1 = PortVlanBinding.newBuilder
                .setId(UUID.randomUUID().asProto)
                .setVlanPortId(b2p1.getId)
                .setTrunkPortId(b1p1.getId)
                .setVlan(10)
                .build
            updateBinding(store, CreateOp(bind1)) should be(true)
            val bind2 = PortVlanBinding.newBuilder
                .setId(UUID.randomUUID().asProto)
                .setVlanPortId(b2p2.getId)
                .setTrunkPortId(b1p2.getId)
                .setVlan(20)
                .build
            updateBinding(store, CreateOp(bind2)) should be(true)
            val bind3 = PortVlanBinding.newBuilder
                .setId(UUID.randomUUID().asProto)
                .setVlanPortId(b3p1.getId)
                .setTrunkPortId(b1p1.getId)
                .setVlan(30)
                .build
            updateBinding(store, CreateOp(bind3)) should be(true)
            val bind4 = PortVlanBinding.newBuilder
                .setId(UUID.randomUUID().asProto)
                .setVlanPortId(b3p2.getId)
                .setTrunkPortId(b1p2.getId)
                .setVlan(40)
                .build
            updateBinding(store, CreateOp(bind4)) should be(true)

            // The trunk ports should have 2 inbound rule each (for 2 vlans)
            waitForPortChain(b1p1.getId, 2, true)
            waitForPortChain(b1p2.getId, 2, true)
            // The vlan ports should have 1 outbound rule each
            waitForPortChain(b2p1.getId, 1, false)
            waitForPortChain(b2p2.getId, 1, false)
            waitForPortChain(b3p1.getId, 1, false)
            waitForPortChain(b3p2.getId, 1, false)

            checkPacket("The packet ingresses b1p1 without vlan",
                        "It egresses b1p2 without vlan",
                        packet(b1p1mac, b1p2mac, None),
                        b1p1.getId, b1p2.getId, None)(
                    FlowTagger.tagForPortRx(b1p1.getId),
                    FlowTagger.tagForBridge(vmBridge1.getId),
                    FlowTagger.tagForPortTx(b1p2.getId))
            checkPacket("The packet ingresses b1p1 with vlan 100", //unused
                        "It egresses b1p2 with vlan 100",
                        packet(b1p1mac, b1p2mac, Some(100)),
                        b1p1.getId, b1p2.getId, Some(100))(
                    FlowTagger.tagForPortRx(b1p1.getId),
                    FlowTagger.tagForBridge(vmBridge1.getId),
                    FlowTagger.tagForPortTx(b1p2.getId))
            checkPacket("The packet ingresses b1p1 with vlan 10",
                        "It egresses b1p2 with vlan20",
                        packet(b2p1mac, b2p2mac, Some(10)),
                        b1p1.getId, b1p2.getId, Some(20))(
                    FlowTagger.tagForPortRx(b1p1.getId),
                    FlowTagger.tagForPortRx(b2p1.getId),
                    FlowTagger.tagForBridge(vmBridge2.getId),
                    FlowTagger.tagForPortTx(b2p2.getId),
                    FlowTagger.tagForPortTx(b1p2.getId))
            checkPacket("The packet ingresses b1p1 with vlan 30",
                        "It egresses b1p2 with vlan40",
                        packet(b3p1mac, b3p2mac, Some(30)),
                        b1p1.getId, b1p2.getId, Some(40))(
                    FlowTagger.tagForPortRx(b1p1.getId),
                    FlowTagger.tagForPortRx(b3p1.getId),
                    FlowTagger.tagForBridge(vmBridge3.getId),
                    FlowTagger.tagForPortTx(b3p2.getId),
                    FlowTagger.tagForPortTx(b1p2.getId))
        }
    }
}
