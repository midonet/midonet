/*
 * Copyright 2014 Midokura SARL
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

import scala.concurrent.duration._

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import rx.Observable

import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.models.Topology.{Port => TopologyPort, Network => TopologyBridge, Router => TopologyRouter}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.simulation.{Bridge => SimulationBridge}
import org.midonet.midolman.state.{PathBuilder, ZkManager}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.packets.{MAC, IPv4Addr}
import org.midonet.util.concurrent._
import org.midonet.util.reactivex.AwaitableObserver

@RunWith(classOf[JUnitRunner])
class BridgeMapperTest extends MidolmanSpec with TopologyBuilder
                       with TopologyMatchers with LegacyBuilder {

    private var store: Storage = _
    private var vt: VirtualTopology = _
    private var zk: ZkManager = _
    private var path: PathBuilder = _
    private var config: MidolmanConfig = _

    protected override def beforeTest(): Unit = {
        vt = injector.getInstance(classOf[VirtualTopology])
        store = injector.getInstance(classOf[Storage])
        zk = injector.getInstance(classOf[ZkManager])
        path = injector.getInstance(classOf[PathBuilder])
        config = injector.getInstance(classOf[MidolmanConfig])
    }

    protected override def fillConfig(config: HierarchicalConfiguration)
    : HierarchicalConfiguration = {
        config.setProperty("zookeeper.cluster_storage_enabled", true)
        config.setProperty("midolman.enable_bridge_arp", true)
        config
    }

    private def createObserver: AwaitableObserver[SimulationBridge] = {
        Given("An observer for the bridge mapper")
        new AwaitableObserver[SimulationBridge]
    }

    private def testBridgeCreated(bridgeId: UUID,
                                  obs: AwaitableObserver[SimulationBridge])
    : TopologyBridge = {
        Given("A bridge mapper")
        val mapper = new BridgeMapper(bridgeId, vt)

        And("A bridge")
        val bridge = createBridge(id = bridgeId)

        When("The bridge is created")
        store.create(bridge)
        // Create the bridge in the legacy storage, this is only needed for
        // the bridge's replicated maps.
        ensureLegacyBridge(zk, path, bridgeId)

        And("The observer subscribes to an observable on the mapper")
        Observable.create(mapper).subscribe(obs)

        Then("The observer should receive the bridge device")
        obs.await(5 seconds, 1) shouldBe true
        val device = obs.getOnNextEvents.get(obs.getOnNextEvents.size - 1)
        device shouldBeDeviceOf bridge

        bridge
    }

    private def testBridgeUpdated(bridge: TopologyBridge,
                                  obs: AwaitableObserver[SimulationBridge])
    : SimulationBridge = {
        When("The bridge is updated")
        store.update(bridge)

        Then("The observer should receive the update")
        obs.await(5 seconds, 1) shouldBe true
        val device = obs.getOnNextEvents.get(obs.getOnNextEvents.size - 1)
        device shouldBeDeviceOf bridge

        device
    }

    private def testBridgeDeleted(bridgeId: UUID,
                                  obs: AwaitableObserver[SimulationBridge])
    : Unit = {
        When("The bridge is deleted")
        store.delete(classOf[TopologyBridge], bridgeId)

        Then("The observer should receive a completed notification")
        obs.await(5 seconds, 1) shouldBe true
        obs.getOnCompletedEvents should not be empty
    }

    feature("Bridge mapper emits notifications for bridge update") {
        scenario("The mapper emits error for non-existing bridge") {
            Given("A bridge identifier")
            val bridgeId = UUID.randomUUID

            And("A bridge mapper")
            val mapper = new BridgeMapper(bridgeId, vt)

            And("An observer to the bridge mapper")
            val obs = new AwaitableObserver[SimulationBridge]

            When("The observer subscribes to an observable on the mapper")
            Observable.create(mapper).subscribe(obs)

            Then("The observer should see a NotFoundException")
            obs.await(5 seconds) shouldBe true
            val e = obs.getOnErrorEvents.get(0).asInstanceOf[NotFoundException]
            e.clazz shouldBe classOf[TopologyBridge]
            e.id shouldBe bridgeId
        }

        scenario("The mapper emits existing bridge") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver
            testBridgeCreated(bridgeId, obs)
        }

        scenario("The mapper emits new device on bridge update") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver
            testBridgeCreated(bridgeId, obs)
            val bridgeUpdate = createBridge(id = bridgeId, adminStateUp = true)
            testBridgeUpdated(bridgeUpdate, obs)
        }

        scenario("The mapper completes on bridge delete") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver
            testBridgeCreated(bridgeId, obs)
            testBridgeDeleted(bridgeId, obs)
        }
    }

    feature("Test port updates") {
        scenario("Create port neither interior nor exterior") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver
            val bridge = testBridgeCreated(bridgeId, obs)

            When("Creating an exterior port for the bridge")
            val portId = UUID.randomUUID
            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId))
            store.create(port)

            Then("The observer should receive the update")
            obs.await(5 seconds, 1) shouldBe true
            val device = obs.getOnNextEvents.get(obs.getOnNextEvents.size - 1)
            device shouldBeDeviceOf bridge
            device.exteriorPorts shouldBe empty
            device.vlanToPort.isEmpty shouldBe true
        }

        scenario("Create and delete exterior port") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver
            val bridge = testBridgeCreated(bridgeId, obs)

            When("Creating a first exterior port for the bridge")
            val portId1 = UUID.randomUUID
            val port1 = createBridgePort(id = portId1, bridgeId = Some(bridgeId),
                                         hostId = Some(UUID.randomUUID),
                                         interfaceName = Some("iface"))
            store.create(port1)

            Then("The observer should receive the update")
            obs.await(5 seconds, 1) shouldBe true
            val device1 = obs.getOnNextEvents.get(obs.getOnNextEvents.size - 1)
            device1 shouldBeDeviceOf bridge

            And("There should be one exterior port but no VLAN ports")
            device1.exteriorPorts should contain only portId1
            device1.vlanToPort.isEmpty shouldBe true

            When("Creating a second exterior port for the bridge")
            val portId2 = UUID.randomUUID
            val port2 = createBridgePort(id = portId2, bridgeId = Some(bridgeId),
                                         hostId = Some(UUID.randomUUID),
                                         interfaceName = Some("iface"))
            store.create(port2)

            Then("The observer should receive the update")
            obs.await(5 seconds, 1) shouldBe true
            val device2 = obs.getOnNextEvents.get(obs.getOnNextEvents.size - 1)
            device2 shouldBeDeviceOf bridge

            And("There should be the two exterior ports but no VLAN ports")
            device2.exteriorPorts should contain allOf (portId1, portId2)
            device2.vlanToPort.isEmpty shouldBe true

            When("Deleting the first exterior port")
            store.delete(classOf[TopologyPort], portId1)
        }

        scenario("Create interior port no VLAN peered to a bridge port no VLAN") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver
            val bridge = testBridgeCreated(bridgeId, obs)

            When("Creating a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId),
                                            peerId = Some(portId))
            store.create(peerBridge)
            store.create(peerPort)
            store.get(classOf[TopologyBridge], peerBridgeId).await()
            store.get(classOf[TopologyPort], peerPortId).await()

            And("Creating an interior port for the bridge")
            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                        peerId = Some(peerPortId))
            store.create(port)

            Then("The observer should receive the update")
            obs.await(5 seconds, 1) shouldBe true
            val device = obs.getOnNextEvents.get(obs.getOnNextEvents.size - 1)
            device shouldBeDeviceOf bridge

            And("There should be no exterior ports or VLAN ports")
            device.exteriorPorts shouldBe empty
            device.vlanToPort.isEmpty shouldBe true

            And("The bridge VLAN peer port ID should be None")
            device.vlanPortId shouldBe None
        }

        scenario("Create interior port no VLAN peer to a bridge port VLAN") {
            val bridgeId = UUID.randomUUID
            val vlanId: Short = 1
            val obs = createObserver
            val bridge = testBridgeCreated(bridgeId, obs)

            When("Creating a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId),
                                            peerId = Some(portId),
                                            vlanId = Some(vlanId))
            store.create(peerBridge)
            store.create(peerPort)
            store.get(classOf[TopologyBridge], peerBridgeId).await()
            store.get(classOf[TopologyPort], peerPortId).await()

            And("Creating an interior port for the bridge")
            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                        peerId = Some(peerPortId))
            store.create(port)

            Then("The observer should receive the update")
            obs.await(5 seconds, 1) shouldBe true
            val device = obs.getOnNextEvents.get(obs.getOnNextEvents.size - 1)
            device shouldBeDeviceOf bridge

            And("There should be no exterior ports or VLAN ports")
            device.exteriorPorts shouldBe empty
            device.vlanToPort.isEmpty shouldBe true

            And("The bridge VLAN peer port ID should be the peer port")
            device.vlanPortId shouldBe Some(peerPortId)
        }

        scenario("Create interior port VLAN peered to a bridge port no VLAN") {
            val bridgeId = UUID.randomUUID
            val vlanId: Short = 1
            val obs = createObserver
            val bridge = testBridgeCreated(bridgeId, obs)
            ensureLegacyBridgeVlan(zk, path, bridgeId, vlanId)

            When("Creating a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId),
                                            peerId = Some(portId))
            store.create(peerBridge)
            store.create(peerPort)
            store.get(classOf[TopologyBridge], peerBridgeId).await()
            store.get(classOf[TopologyPort], peerPortId).await()

            And("Creating an interior port for the bridge")
            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                        peerId = Some(peerPortId),
                                        vlanId = Some(vlanId))
            store.create(port)

            Then("The observer should receive the update")
            obs.await(5 seconds, 1) shouldBe true
            val device = obs.getOnNextEvents.get(obs.getOnNextEvents.size - 1)
            device shouldBeDeviceOf bridge

            And("There should be no exterior ports")
            device.exteriorPorts shouldBe empty

            And("There should be one interior VLAN port")
            device.vlanToPort.getPort(vlanId) shouldBe portId
            device.vlanToPort.getVlan(portId) shouldBe vlanId

            And("The bridge VLAN peer port ID should be None")
            device.vlanPortId shouldBe None
        }

        scenario("Create interior port VLAN peered to a bridge port VLAN") {
            val bridgeId = UUID.randomUUID
            val vlanId: Short = 1
            val peerVlanId: Short = 2
            val obs = createObserver
            val bridge = testBridgeCreated(bridgeId, obs)
            ensureLegacyBridgeVlan(zk, path, bridgeId, vlanId)

            When("Creating a peer bridge and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerBridgeId = UUID.randomUUID

            val peerBridge = createBridge(id = peerBridgeId)
            val peerPort = createBridgePort(id = peerPortId,
                                            bridgeId = Some(peerBridgeId),
                                            peerId = Some(portId),
                                            vlanId = Some(peerVlanId))
            store.create(peerBridge)
            store.create(peerPort)
            store.get(classOf[TopologyBridge], peerBridgeId).await()
            store.get(classOf[TopologyPort], peerPortId).await()

            And("Creating an interior port for the bridge")
            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                       peerId = Some(peerPortId),
                                       vlanId = Some(vlanId))
            store.create(port)

            Then("The observer should receive the update")
            obs.await(5 seconds, 1) shouldBe true
            val device = obs.getOnNextEvents.get(obs.getOnNextEvents.size - 1)
            device shouldBeDeviceOf bridge

            And("There should be no exterior ports")
            device.exteriorPorts shouldBe empty

            And("There should be one interior VLAN port")
            device.vlanToPort.getPort(vlanId) shouldBe portId
            device.vlanToPort.getVlan(portId) shouldBe vlanId

            And("The bridge VLAN peer port ID should be None")
            device.vlanPortId shouldBe None
        }

        scenario("Create interior port no VLAN peered to a router port") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver
            val bridge = testBridgeCreated(bridgeId, obs)

            When("Creating a peer router and port")
            val portId = UUID.randomUUID
            val peerPortId = UUID.randomUUID
            val peerRouterId = UUID.randomUUID
            val peerPortAddr = IPv4Addr.random
            val peerPortMac = MAC.random

            val peerRouter = createRouter(id = peerRouterId)
            val peerPort = createRouterPort(id = peerPortId,
                                            routerId = Some(peerRouterId),
                                            peerId = Some(portId),
                                            portAddress = peerPortAddr,
                                            portMac = peerPortMac)
            store.create(peerRouter)
            store.create(peerPort)
            store.get(classOf[TopologyRouter], peerRouterId).await()
            store.get(classOf[TopologyPort], peerPortId).await()

            And("Creating an interior port for the bridge")
            val port = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                 peerId = Some(peerPortId))
            store.create(port)

            Then("The observer should receive the update")
            obs.await(5 seconds, 1) shouldBe true
            val device = obs.getOnNextEvents.get(obs.getOnNextEvents.size - 1)
            device shouldBeDeviceOf bridge

            And("There should be no exterior ports")
            device.exteriorPorts shouldBe empty

            And("The bridge VLAN peer port ID should be None")
            device.vlanPortId shouldBe None

            And("The IP-MAC mapping for the router port be set")
            device.ipToMac.toSeq should contain only ((peerPortAddr, peerPortMac))
        }

        // TODO: Add more unit tests, pending merging of the updated
        // InMemoryStorage, which handles atomic transactions and does not
        // generate intermediate notifications.

        /*
        scenario("Update exterior port") {
            val bridgeId = UUID.randomUUID
            val obs = createObserver
            val bridge = testBridgeCreated(bridgeId, obs)

            When("Creating a first exterior port for the bridge")
            val portId = UUID.randomUUID
            val hostId = UUID.randomUUID
            val port1 = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                         hostId = Some(hostId),
                                         interfaceName = Some("iface0"))
            store.create(port1)

            Then("The observer should receive the update")
            obs.await(5 seconds, 1) shouldBe true
            val device1 = obs.getOnNextEvents.get(obs.getOnNextEvents.size - 1)
            device1 shouldBeDeviceOf bridge

            And("There should be one exterior port but no VLAN ports")
            device1.exteriorPorts should contain only portId
            device1.vlanToPort.isEmpty shouldBe true

            When("The exterior port is updated")
            val port2 = createBridgePort(id = portId, bridgeId = Some(bridgeId),
                                         hostId = Some(hostId),
                                         interfaceName = Some("iface1"))
            store.update(port2)

            Then("The observer should receive the update")
            obs.await(5 seconds, 1) shouldBe true
            val device2 = obs.getOnNextEvents.get(obs.getOnNextEvents.size - 1)
            device2 shouldBeDeviceOf bridge

            And("There should be one exterior port but no VLAN ports")
            device2.exteriorPorts should contain only portId
            device2.vlanToPort.isEmpty shouldBe true
        }

        scenario("Update interior port no VLAN to VLAN") {
        }

        scenario("Update interior port VLAN to no VLAN") {
        }

        scenario("Update peer port no VLAN to VLAN (local port has no VLAN)") {
        }

        scenario("Update peer port no VLAN to VLAN (local port has VLAN)") {
        }

        scenario("Update peer router port") {
        }

        scenario("Delete interior port") {
        }

        */
    }

    feature("Test MAC learning tables") {
        scenario("MAC learning table created") {
        }

        scenario("MAC learning table deleted") {
        }
    }

    feature("Test flow invalidations") {
        scenario("For changes in exterior ports") {
        }

        scenario("For added MAC-port mappings") {
        }

        scenario("For deleted MAC-port mappings") {
        }

        scenario("For MAC added to port") {
        }

        scenario("For MAC changed to a different port") {
        }

        scenario("For MAC deleted from port") {
        }
    }
}
