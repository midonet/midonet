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
import org.midonet.cluster.models.ServiceChainTranslation._
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.simulation.{Bridge, Chain => SimChain, Port => SimPort}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.Ethernet
import org.midonet.util.concurrent.FutureOps
import org.midonet.util.reactivex._

@RunWith(classOf[JUnitRunner])
class ServiceChainSimulationTest extends MidolmanSpec with TopologyBuilder {

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
                   srcInsertions: Seq[ServiceChainElem],
                   dstInsertions: Seq[ServiceChainElem]) = {
        var prevPortId: UUID = srcPortId
        var prevVlan: Option[Short] = None
        var nextVlan: Option[Short] = None
        var i: Int = 0
        def checkInsertions(insertions: Seq[ServiceChainElem], inbound: Boolean) = {
            insertions.foreach(
                x =>  {
                    inbound match {
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
                            inbound match {
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
                       srcInsertions: Seq[ServiceChainElem],
                       dstInsertions: Seq[ServiceChainElem] = Seq.empty[ServiceChainElem]
                          ) = {
        // First we check the forward path
        checkInsertionPath(srcPortId, dstPortId, srcMac, dstMac,
                           srcInsertions, dstInsertions)
        // Then we check the return path
        checkInsertionPath(dstPortId, srcPortId, dstMac, srcMac,
                           dstInsertions, srcInsertions)
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

    feature("Test redirect without vlans") {
        scenario("Test") {
            val svc1mac = "02:00:00:00:ee:00"
            val svc2mac = "02:00:00:00:ee:11"
            val vm1br1mac = "02:00:00:00:ee:22"
            val vm2br1mac = "02:00:00:00:ee:33"
            val vm1br2mac = "02:00:00:00:ee:44"
            val vm2br2mac = "02:00:00:00:ee:55"

            val host = createHost()
            store.create(host)

            val vmBridge1 = createBridge(name = Some("vmBridge1"),
                                         adminStateUp = true)
            store.create(vmBridge1)
            val vmBridge2 = createBridge(name = Some("vmBridge2"),
                                         adminStateUp = true)
            store.create(vmBridge2)
            val svcBridge = createBridge(name = Some("svcBridge"),
                                         adminStateUp = true)
            store.create(svcBridge)

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
            val vm1br1 = bridgePort("vm1br1_if", vmBridge1)
            val vm2br1 = bridgePort("vm2br1_if", vmBridge1)
            // Two VMs on bridge2
            val vm1br2 = bridgePort("vm1br2_if", vmBridge2)
            val vm2br2 = bridgePort("vm2br2_if", vmBridge2)
            // Two services on the service bridge
            val svc1 = bridgePort("svc1_if", svcBridge)
            val svc2 = bridgePort("svc2_if", svcBridge)

            // Load the cache to avoid NotYetException at simulation time
            for (port <- List(vm1br1, vm2br1, vm1br2, vm2br2,
                              svc1, svc2))
                tryGet(() => VirtualTopology.tryGet[SimPort](port.getId))
            for (bridge <- List(vmBridge1, vmBridge2, svcBridge))
                tryGet(() => VirtualTopology.tryGet[Bridge](bridge.getId))

            // Set all the ports to "Active"
            val hostIdString = fromProto(host.getId).toString
            for (port <- List(vm1br1, vm2br1, vm1br2, vm2br2,
                              svc1, svc2)) {
                stateStore.addValue(classOf[Port], port.getId, HostsKey,
                                    hostIdString).await(3 seconds)
            }

            // Before adding insertions, send forward and return packets to
            // seed the MAC tables. This avoids bridge flood actions and
            // allows 'checkPacket' to always check for a single output port.
            simulate(packetContextFor(packet(vm1br1mac, vm2br1mac), vm1br1.getId))
            simulate(packetContextFor(packet(vm2br1mac, vm1br1mac), vm2br1.getId))
            simulate(packetContextFor(packet(vm1br2mac, vm2br2mac), vm1br2.getId))
            simulate(packetContextFor(packet(vm2br2mac, vm1br2mac), vm2br2.getId))

            // Create service objects for both svc ports.
            var service1 = Service.newBuilder()
                .setId(UUID.randomUUID).setMac(svc1mac).setPort(svc1.getId).build
            updateService(store, CreateOp(service1)) should be(true)
            var service2 = Service.newBuilder()
                .setId(UUID.randomUUID).setMac(svc2mac).setPort(svc2.getId).build
            updateService(store, CreateOp(service2)) should be(true)

            // Create service chains on the 1st port of each bridge.
            var chain1 = ServiceChain.newBuilder()
                .setId(UUID.randomUUID).setName("chain1").setPort(vm1br1.getId).build
            updateChain(store, CreateOp(chain1)) should be(true)
            var chain2 = ServiceChain.newBuilder()
                .setId(UUID.randomUUID).setName("chain2").setPort(vm1br2.getId).build
            updateChain(store, CreateOp(chain2)) should be(true)

            // Add svc1 and svc2 to both chains, but in different order
            var elem1chain1 = ServiceChainElem.newBuilder()
                .setId(UUID.randomUUID).setServiceId(service1.getId)
                .setChainId(chain1.getId).build
            updateChain(store, CreateOp(elem1chain1)) should be(true)
            var elem2chain1 = ServiceChainElem.newBuilder()
                .setId(UUID.randomUUID).setServiceId(service2.getId)
                .setChainId(chain1.getId).build
            updateChain(store, CreateOp(elem2chain1)) should be(true)

            var elem1chain2 = ServiceChainElem.newBuilder()
                .setId(UUID.randomUUID).setServiceId(service2.getId)
                .setChainId(chain2.getId).build
            updateChain(store, CreateOp(elem1chain2)) should be(true)
            var elem2chain2 = ServiceChainElem.newBuilder()
                .setId(UUID.randomUUID).setServiceId(service1.getId)
                .setChainId(chain2.getId).build
            updateChain(store, CreateOp(elem2chain2)) should be(true)

            waitForPortChain(vm1Port.getId, 3, true)
            waitForPortChain(vm1Port.getId, 2, false)
            waitForPortChain(svc1Port.getId, 2, true)

            // Now check the insertion on vm1
            checkRoundTrip(vm1br1.getId, vm2br1.getId,
                           vm1br1mac, vm2br1mac, Seq.empty[ServiceChainElem])

            // Now add another insertion vm1 -> svc2
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

            // Now add an insertion on vm2 (same bridge as vm1)
            val vm2ins1 = L2Insertion.newBuilder
                .setId(UUID.randomUUID().asProto)
                .setMac(mac2)
                .setPort(vm2Port.getId)
                .setSrvPort(srv1Port.getId)
                .setPosition(1)
                .setVlan(10)
                .build
            updateInsertions(store, CreateOp(vm2ins1)) should be(true)
            waitForPortChain(vm2Port.getId, 3, true)
            waitForPortChain(vm2Port.getId, 2, false)
            waitForPortChain(srv1Port.getId, 4, true)

            // Now check the concurrent insertions on vm1 and vm2 (same bridge)
            checkRoundTrip(vm1Port.getId, vm2Port.getId,
                           mac1, mac2, Seq(vm1ins1, vm1ins2), Seq(vm2ins1))
            // Now delete the insertion on vm2
            updateInsertions(store,
                             DeleteOp(classOf[L2Insertion],
                                      vm2ins1.getId)) should be(true)
            waitForPortChain(vm2Port.getId, 0, true)
            waitForPortChain(vm2Port.getId, 0, false)
            waitForPortChain(srv1Port.getId, 2, true)

            // Now modify the insertion vm1->srv1 to use vlan 30.
            vm1ins1 = vm1ins1.toBuilder.setVlan(30).build
            updateInsertions(store, UpdateOp(vm1ins1)) should be(true)
            eventually (timeout(Span(2, Seconds))) {
                val port = VirtualTopology.tryGet[SimPort](vm1ins1.getPort)
                val chain = VirtualTopology
                    .tryGet[SimChain](port.inboundChains(0))
                chain.rules.get(3).pushVlan should be(30 | 1 << 13)
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
                chain.rules.get(3).pushVlan should be(0)
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

            // Now check the insertion on vm3
            checkRoundTrip(vm3Port.getId, vm4Port.getId,
                           mac3, mac4, Seq(vm3ins1))

            // Now set srv1Port to down. Verify traffic still flows thanks to
            // fail_open=true.
            stateStore.removeValue(classOf[Port], srv1Port.getId, HostsKey,
                                hostIdString).await(3 seconds)

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
