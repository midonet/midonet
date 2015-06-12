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


import org.midonet.cluster.data.storage.{CreateOp, Storage}
import org.midonet.cluster.models.L2InsertionTranslation._
import org.midonet.cluster.models.Topology.{L2Insertion, Port, Chain}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.simulation.{Bridge, Chain => SimChain}
import org.midonet.midolman.topology.devices.{Port => SimPort}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.concurrent.FutureOps

@RunWith(classOf[JUnitRunner])
class L2InsertionSimulationTest extends MidolmanSpec with TopologyBuilder {

    private var vt: VirtualTopology = _
    private var store: Storage = _

    private val bridgeId = UUID.randomUUID
    private val mac1 = "02:00:00:00:ee:00"
    private val mac2 = "02:00:00:00:ee:11"

    registerActors(
        VirtualTopologyActor -> (() => new VirtualTopologyActor()))

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[MidonetBackend]).store
        store.create(createBridge(id = bridgeId))
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

    def packet = {
        import org.midonet.packets.util.PacketBuilder._

        {
            eth addr mac1 -> mac2
        } << {
            ip4 addr "10.0.0.10" --> "10.0.0.11"
        } << {
            udp ports 53 ---> 54
        } <<
        payload(UUID.randomUUID().toString)
    }

    def packetWithVlan(vlan: Short) = {
        import org.midonet.packets.util.PacketBuilder._

        {
            eth addr mac1 -> mac2 vlan (vlan)
        } << {
            ip4 addr "10.0.0.10" --> "10.0.0.11"
        } << {
            udp ports 53 ---> 54
        } <<
        payload(UUID.randomUUID().toString)
    }

    def returnPacket = {
        import org.midonet.packets.util.PacketBuilder._

        {
            eth addr mac2 -> mac1
        } << {
            ip4 addr "10.0.0.11" --> "10.0.0.10"
        } << {
            udp ports 54 ---> 53
        } <<
        payload(UUID.randomUUID().toString)
    }

    def returnPacketWithVlan(vlan: Short) = {
        import org.midonet.packets.util.PacketBuilder._

        {
            eth addr mac2 -> mac1 vlan (vlan)
        } << {
            ip4 addr "10.0.0.11" --> "10.0.0.10"
        } << {
            udp ports 54 ---> 53
        } <<
        payload(UUID.randomUUID().toString)
    }

    feature("Test redirect without vlans") {
        scenario("Test") {
            val host = createHost()

            val vmBridge = createBridge(name = Some("vmBridge"),
                                        adminStateUp = true)
            val srvBridge = createBridge(name = Some("srvBridge"),
                                         adminStateUp = true)

            var vm1Port = createBridgePort(
                hostId = Some(host.getId),
                interfaceName = Some("vm1_if"),
                adminStateUp = true,
                bridgeId = Some(vmBridge.getId))

            var vm2Port = createBridgePort(
                hostId = Some(host.getId),
                interfaceName = Some("vm2_if"),
                adminStateUp = true,
                bridgeId = Some(vmBridge.getId))

            var srv1Port = createBridgePort(
                hostId = Some(host.getId),
                interfaceName = Some("srv1_if"),
                adminStateUp = true,
                bridgeId = Some(srvBridge.getId))

            var srv2Port = createBridgePort(
                hostId = Some(host.getId),
                interfaceName = Some("srv2_if"),
                adminStateUp = true,
                bridgeId = Some(srvBridge.getId))

            List(host, vmBridge, srvBridge, vm1Port, vm2Port,
                 srv1Port, srv2Port).foreach {
                store.create(_)
            }
            updateInsertions(
                store, CreateOp(
                    L2Insertion.newBuilder
                        .setId(UUID.randomUUID().asProto)
                        .setMac(mac1)
                        .setPort(vm1Port.getId)
                        .setSrvPort(srv1Port.getId)
                        .setPosition(1)
                        .setVlan(10)
                        .build)) should be(true)

            Thread.sleep(2000)

            vm1Port = store.get(classOf[Port], vm1Port.getId).await()
            vm2Port = store.get(classOf[Port], vm2Port.getId).await()
            srv1Port = store.get(classOf[Port], srv1Port.getId).await()
            srv2Port = store.get(classOf[Port], srv2Port.getId).await()
            val c1in = store.get(classOf[Chain], vm1Port.getInboundChain(0)).await()
            val c1out = store.get(classOf[Chain], vm1Port.getOutboundChain(0)).await()
            val srv1in = store.get(classOf[Chain], srv1Port.getInboundChain(0)).await()
            val srv1out = store.get(classOf[Chain], srv1Port.getOutboundChain(0)).await()

            // Load the cache to avoid NotYetException at simulation time
            for (port <- List(vm1Port, vm2Port, srv1Port, srv2Port))
                tryGet(() => VirtualTopology.tryGet[SimPort](port.getId))
            tryGet(() => VirtualTopology.tryGet[Bridge](vmBridge.getId))
            tryGet(() => VirtualTopology.tryGet[Bridge](srvBridge.getId))

            // Set all the ports to "Active"
            for (port <- List(vm1Port, vm2Port, srv1Port, srv2Port)) {
                VirtualTopology.tryGet[SimPort](port.getId)._active = true
            }

            var c1inS, c1outS, srv1inS, srv1outS: SimChain = null
            eventually (timeout(Span(2, Seconds))) {
                c1inS = VirtualTopology.tryGet[SimChain](vm1Port.getInboundChain(0))
                c1inS.getRules.size should be(2)
            }
            eventually (timeout(Span(2, Seconds))) {
                c1outS = VirtualTopology.tryGet[SimChain](vm1Port.getOutboundChain(0))
                c1outS.getRules.size should be(2)
            }
            eventually (timeout(Span(2, Seconds))) {
                srv1inS = VirtualTopology.tryGet[SimChain](srv1Port.getInboundChain(0))
                srv1inS.getRules.size should be(2)
            }
            eventually (timeout(Span(2, Seconds))) {
                srv1outS = VirtualTopology.tryGet[SimChain](srv1Port.getOutboundChain(0))
                srv1outS.getRules.size should be(0)
            }

            When("A packet ingresses vm1's port")
            var packetContext = packetContextFor(packet, vm1Port.getId)
            var result = simulate(packetContext)

            Then("It's redirected out srv1's port")
            //result should be(hasPushVlan(10))
            result should be(toPort(srv1Port.getId)
                                 (FlowTagger.tagForDevice(vm1Port.getId),
                                  FlowTagger.tagForDevice(srv1Port.getId)))
            result._2.wcmatch.getVlanIds.get(0) should be(10)

            When("The packet ingresses srv1's port with vlan 10")
            packetContext = packetContextFor(packetWithVlan(10), srv1Port.getId)
            result = simulate(packetContext)

            Then("It's redirected into vm1's port and floods the bridge")
            //result should be(hasPopVlan())
            result should be(toBridge(vmBridge.getId,
                                      List(vm2Port.getId),
                                      FlowTagger.tagForDevice(srv1Port.getId),
                                      FlowTagger.tagForDevice(vm1Port.getId),
                                      FlowTagger.tagForDevice(vmBridge.getId),
                                      FlowTagger.tagForDevice(vm2Port.getId)))
            result._2.wcmatch.getVlanIds.size() should be(0)
        }
    }
}
