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
                            //x.getPort should be(srcPortId.asProto)
                        case false =>
                            x.getMac should be(dstMac)
                            //x.getPort should be(dstPortId.asProto)
                    }
                    nextVlan = x.getVlan match {
                        case 0 => None
                        case v =>
                            inbound match {
                                case true => Some((v | 1 << 13).toShort)
                                case false => Some((v | 2 << 13).toShort)
                            }
                    }
//                    checkPacket("The packet ingresses the previous port",
//                                "It's redirected out srv port-" + i,
//                                packet(srcMac, dstMac, prevVlan),
//                                prevPortId, x.getSrvPort, nextVlan)
                    i = i+1
                    prevVlan = nextVlan
                    //prevPortId = x.getSrvPort
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

            checkRoundTrip(vm1br1.getId, vm2br1.getId,
                           vm1br1mac, vm2br1mac, Seq.empty[ServiceChainElem])

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
                .setChainId(chain1.getId).setVlan(10).setPosition(1)
                .setFailOpen(true).build // Use fail-open only on this element
            updateElements(store, CreateOp(elem1chain1)) should be(true)

            // TODO: wait the translation to update the simulation objects
            waitForPortChain(vm1br1.getId, 3, true)
            waitForPortChain(svc1.getId, 2, false)
            checkRoundTrip(vm1br1.getId, vm2br1.getId,
                           vm1br1mac, vm2br1mac, Seq(elem1chain1))

            var elem2chain1 = ServiceChainElem.newBuilder()
                .setId(UUID.randomUUID).setServiceId(service2.getId)
                .setChainId(chain1.getId).setVlan(20).setPosition(2).build
            updateElements(store, CreateOp(elem2chain1)) should be(true)

            checkRoundTrip(vm1br1.getId, vm2br1.getId,
                           vm1br1mac, vm2br1mac, Seq(elem1chain1, elem2chain1))

            var elem1chain2 = ServiceChainElem.newBuilder()
                .setId(UUID.randomUUID).setServiceId(service2.getId)
                .setChainId(chain2.getId).setVlan(30).setPosition(1).build
            updateElements(store, CreateOp(elem1chain2)) should be(true)
            var elem2chain2 = ServiceChainElem.newBuilder()
                .setId(UUID.randomUUID).setServiceId(service1.getId)
                .setChainId(chain2.getId).setVlan(40).setPosition(2).build
            updateElements(store, CreateOp(elem2chain2)) should be(true)

            // The first chain should still work correctly
            checkRoundTrip(vm1br1.getId, vm2br1.getId,
                           vm1br1mac, vm2br1mac, Seq(elem1chain1, elem2chain1))
            // But now the second chain also still works.
            checkRoundTrip(vm1br2.getId, vm2br2.getId,
                           vm1br2mac, vm2br2mac, Seq(elem1chain2, elem2chain2))

            // Set svc1 to down. Verify traffic still flows thanks to
            // fail_open=true.
            stateStore.removeValue(classOf[Port], svc1.getId, HostsKey,
                                   hostIdString).await(3 seconds)
            checkRoundTrip(vm1br1.getId, vm2br1.getId,
                           vm1br1mac, vm2br1mac, Seq(elem2chain1))

            // Modify the 1st element of the first chain to use vlan 100.
            // TODO

            // Now delete the 1st element of the first chain.
            updateElements(store,
                           DeleteOp(classOf[ServiceChainElem],
                                    elem1chain1.getId)) should be(true)
            checkRoundTrip(vm1br1.getId, vm2br1.getId,
                           vm1br1mac, vm2br1mac, Seq(elem2chain1))

            // Now delete the 2nd element of the first chain.
            updateElements(store,
                           DeleteOp(classOf[ServiceChainElem],
                                    elem2chain1.getId)) should be(true)
            checkRoundTrip(vm1br1.getId, vm2br1.getId,
                           vm1br1mac, vm2br1mac, Seq.empty[ServiceChainElem])
    }
}
