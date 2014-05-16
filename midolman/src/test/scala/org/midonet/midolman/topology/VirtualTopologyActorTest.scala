/*
* Copyright 2014 Midokura Europe SARL
*/
package org.midonet.midolman.topology

import java.util.UUID
import scala.concurrent.duration._

import akka.actor.{Actor, Props}
import akka.testkit.TestActorRef
import akka.util.Timeout

import org.midonet.cluster.client.Port
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.cluster.data.{Bridge => ClusterBridge}
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.simulation.{Chain, IPAddrGroup, LoadBalancer, Bridge => SimulationBridge, Router => SimulationRouter}
import org.midonet.midolman.topology.VirtualTopologyActor.{BridgeRequest, DeviceRequest, PortRequest, Unsubscribe}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator

/**
 * Test case for the Virtual Topology Actor requests
 */
class VirtualTopologyActorTest extends MidolmanSpec {

    def registerActors = List(VirtualTopologyActor -> (() =>
        new VirtualTopologyActor with MessageAccumulator))

    var bridge: ClusterBridge = _
    var port: BridgePort = _

    implicit val timeout = Timeout(5 seconds)

    // topology devices returned by the VTA
    var receivedTopology = Topology()

    // An actor that receives topology updated from the VTA
    class TopologySubscriber extends Actor with ActorLogWithoutPath {

        def receive = {
            // requests to the VTA
            case r: DeviceRequest =>
                VirtualTopologyActor ! r

            case u: Unsubscribe =>
                VirtualTopologyActor ! u

            // responses from the VTA
            case port: Port => receivedDevice(port.id, port)
            case bridge: SimulationBridge => receivedDevice(bridge.id, bridge)
            case router: SimulationRouter => receivedDevice(router.id, router)
            case chain: Chain => receivedDevice(chain.id, chain)
            case group: IPAddrGroup => receivedDevice(group.id, group)
            case lb: LoadBalancer => receivedDevice(lb.id, lb)

            case unknown =>
                log.error("Received unknown message {}",
                          unknown.getClass.getSimpleName)
                fail()
        }

        private def receivedDevice(id: UUID, dev: AnyRef): Unit = {
            log.info("Received a device {} [id={}] from the VTA",
                dev.getClass.getSimpleName, id)
            receivedTopology.put(id, dev)
        }
    }

    var topologySubscriberActor: TestActorRef[TopologySubscriber] = _

    override def beforeTest() {
        bridge = newBridge("bridge0")
        port = newBridgePort(bridge)

        topologySubscriberActor = TestActorRef(Props(new TopologySubscriber()))
    }

    feature("Device managers have proper name and path") {
        scenario("A device manager should have an expected name") {
            Given("A device Id")
            val deviceId = UUID.randomUUID()

            Then("The manager name should match the expected path")
            val vta = Class
                .forName("org.midonet.midolman.topology.VirtualTopologyActor")
            for (method <- vta.getMethods()
                if method.getName().endsWith("ManagerName")) {
                    if (method.getParameterTypes().length == 1) {
                        val managerName = method.invoke(null, deviceId)
                        val methodName = method.getName()
                        val expectedName =
                            methodName.substring(0, methodName.length() - 4)
                                .capitalize + "-" + deviceId
                        managerName should be(expectedName)
                    }
                }
        }

        scenario("A device manager should have an expected path") {
            Given("A device Id")
            val deviceId = UUID.randomUUID()

            Then("The manager path should match the expected path")
            val vta = Class
                .forName("org.midonet.midolman.topology.VirtualTopologyActor")
            for (method <- vta.getMethods()
                if method.getName().endsWith("ManagerPath")) {
                    val methodName = method.getName()
                    if (method.getParameterTypes().length == 2 &&
                        methodName != "getDeviceManagerPath") {
                        val managerPath = method.invoke(null, "Parent", deviceId)
                        val expectedPath =
                            "/user/midolman/Parent/" +
                                methodName.substring(0, methodName.length() - 4)
                                    .capitalize + "-" + deviceId
                        managerPath should be(expectedPath)
                    }
                }
        }
    }

    feature("PoolHealthMonitorManager have proper name and path") {
        scenario("A PoolHealthMonitorManager should have an expected name.") {
            When("The PoolHealthMonitorManager is requested")
            val managerName = VirtualTopologyActor
                .poolHealthMonitorManagerName()

            Then("It should match the expected value")
            managerName should be("PoolHealthMonitorMapRequest")
        }

        scenario("A PoolHealthMonitorManager should have an expected path.") {
            Given("A parent actor name")
            val parentActorName = "Parent"

            When("Requesting a manager path")
            val managerPath = VirtualTopologyActor
                .poolHealthMonitorManagerPath(parentActorName)

            Then("The path should correspond to the Parent")
            managerPath should
                be("/user/midolman/Parent/PoolHealthMonitorMapRequest")
        }
    }

    feature("The VTA returns the requested devices") {
        for (update <- List(true, false)) {
            scenario(s"We request a device with update = $update") {
                Given("An empty topology")

                When("Requesting a bridge")
                val bridgeReq = BridgeRequest(bridge.getId, update = update)
                topologySubscriberActor ! bridgeReq

                Then("The VTA should receive a Bridge request")
                VirtualTopologyActor.messages should contain(bridgeReq)

                And("We should get a Bridge")
                val receivedBridge = receivedTopology.device[SimulationBridge](
                    bridge.getId)
                receivedBridge should not be (null)

                When("A Port is requested")
                val portReq = PortRequest(port.getId, update = update)
                topologySubscriberActor ! portReq

                Then("The VTA should have received both requests")
                VirtualTopologyActor.messages should contain(portReq)

                And("We should have received a Port")
                val receivedPort = receivedTopology.device[SimulationBridge](
                    port.getId)
                receivedPort should not be (null)
            }
        }
    }

    feature("Subscribers receive updates for devices when they want it") {
        scenario("Devices in the topology are updated") {
            Given("A topology with a bridge")
            // send a bridge request: we should get a bridge and the subscriber
            // will get registered as subscriber for updates...
            val bridgeReq = BridgeRequest(bridge.getId, update = true)
            val bridgeId = bridge.getId

            When("Sending a bridge request")
            topologySubscriberActor ! bridgeReq

            Then("We get the bridge")
            val receivedBridge = receivedTopology.device[SimulationBridge](
                bridgeId)
            receivedBridge should not be (null)

            When("Updating the bridge")
            val updatedAdminState = false
            val updatedBridge = new SimulationBridge(bridgeId,
                updatedAdminState,
                bridge.getTunnelKey,
                null,
                null, null, null, null,
                null, null, null, null,
                null, null)
            VirtualTopologyActor ! updatedBridge

            Then("We receive an updated bridge")
            val rUpdatedBridge = receivedTopology.device[SimulationBridge](
                bridgeId)
            rUpdatedBridge should not be (null)
            rUpdatedBridge.adminStateUp should be(updatedAdminState)

            When("Updating the bridge again")
            val updatedVxlanPortId = Option(UUID.randomUUID())
            val updatedBridgeAgain = new SimulationBridge(bridgeId,
                updatedAdminState, bridge.getTunnelKey,
                null, null, null, null, null, null,
                updatedVxlanPortId, null, null, null, null)

            Then("We receive a second update")
            val rsUpdatedBridge = receivedTopology.device[SimulationBridge](
                bridgeId)
            rsUpdatedBridge should not be (null)
            rsUpdatedBridge.vxlanPortId should be(updatedVxlanPortId)
        }

        scenario("Devices are updated but we are not interested in updates") {
            Given("A topology with a bridge")
            // send a bridge request: we should get a bridge and the subscriber
            // will get registered as subscriber for updates...
            val bridgeReq = BridgeRequest(bridge.getId, update = false)
            val bridgeId = bridge.getId

            When("Sending a bridge request")
            topologySubscriberActor ! bridgeReq

            Then("We get the bridge")
            val receivedBridge = receivedTopology.device[SimulationBridge](
                bridgeId)
            receivedBridge should not be (null)

            When("Updating the bridge")
            val updatedAdminState = false
            val updatedBridge = new SimulationBridge(bridgeId,
                updatedAdminState,
                bridge.getTunnelKey,
                null,
                null, null, null, null,
                null, null, null, null,
                null, null)
            VirtualTopologyActor ! updatedBridge

            Then("We do not receive an updated bridge")
            val rUpdatedBridge = receivedTopology.device[SimulationBridge](
                bridgeId)
            rUpdatedBridge should be (null)
        }

        for (update <- List(true, false)) {
            scenario(s"Listeners do not receive updates when unsubscribe with update=$update") {
                Given("A topology with a bridge")
                val bridgeReq = BridgeRequest(bridge.getId, update = update)
                val bridgeId = bridge.getId

                Then("We receive a bridge")
                val receivedBridge = receivedTopology.device[SimulationBridge](
                    bridgeId)
                receivedBridge should not be (null)

                When("We unsubscribe from this bridge")
                topologySubscriberActor ! Unsubscribe(bridgeId)

                And("The bridge is updated")
                val updatedBridge = new SimulationBridge(bridgeId, false,
                    bridge.getTunnelKey,
                    null, null, null, null, null, null, null, null, null, null,
                    null)
                VirtualTopologyActor ! updatedBridge

                Then("The VTA has received the unsubscription request")
                VirtualTopologyActor.messages should contain(Unsubscribe(bridgeId))

                And("We do not receive further updates to the bridge")
                val rsUpdatedBridge = receivedTopology.device[SimulationBridge](
                    bridgeId)
                rsUpdatedBridge should be(null)
            }
        }
    }
}
