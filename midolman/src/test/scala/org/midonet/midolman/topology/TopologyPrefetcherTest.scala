/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.topology

import java.util.UUID

import scala.collection.mutable

import akka.actor.Props
import akka.testkit.TestActorRef

import org.midonet.cluster.data.Bridge
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.midolman.simulation.{Bridge => SimBridge}
import org.midonet.midolman.topology.VirtualTopologyActor.{DeviceRequest, Unsubscribe}
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator

class TopologyPrefetcherTest extends MidolmanSpec {
    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor
                                                  with MessageAccumulator))

    var bridge: Bridge = _
    var port: BridgePort = _

    class MyTopologyPrefetcher extends TopologyPrefetcher {
        val requested = mutable.Set[UUID]()
        var topology: Map[UUID, AnyRef] = _

        def topologyReady() {
            topology = (requested map { id => id -> device(id) }).toMap
        }

        def get[D](id: UUID) = topology.get(id).asInstanceOf[D]

        override def receive = super.receive orElse {
            case reqs: List[_] =>
                val devReqs = reqs.asInstanceOf[List[DeviceRequest]]
                devReqs foreach { requested += _.id }
                prefetchTopology(devReqs: _*)
            case req: DeviceRequest =>
                prefetchTopology(req)
                requested += req.id
        }
    }

    var topologyActor: TestActorRef[MyTopologyPrefetcher] = _

    override def beforeTest() {
        topologyActor = TestActorRef(Props(new MyTopologyPrefetcher()))

        bridge = newBridge("bridge0")
        port = newBridgePort(bridge)
        fetchTopology(bridge, port)
    }

    feature("A hook method is called when the VTA sends the specified devices") {
        scenario("The initial topology is empty") {
            Given("An empty topology")

            When("Pre-fetching a bridge")
            val bridgeReq = topologyActor.underlyingActor.bridge(bridge.getId)
            topologyActor ! bridgeReq

            Then("The hook method is called with the pre-fetched bridge")
            topologyActor.underlyingActor.topology should contain key bridge.getId
            topologyActor.underlyingActor.topology.size should be (1)

            And("The VTA receives a request for the bridge")
            VirtualTopologyActor.messages should contain (bridgeReq)
        }

        scenario("The topology changes") {
            Given("A topology with a bridge")
            val bridgeReq = topologyActor.underlyingActor.bridge(bridge.getId)
            topologyActor ! bridgeReq
            VirtualTopologyActor.getAndClear() should contain (bridgeReq)

            When("Pre-fetching a port and forgetting the bridge")
            val portReq = topologyActor.underlyingActor.port(port.getId)
            topologyActor ! portReq

            Then("The hook method is called with the pre-fetched port")
            topologyActor.underlyingActor.topology should contain key port.getId
            topologyActor.underlyingActor.topology.size should be (1)

            And("The VTA receives a request for the port")
            VirtualTopologyActor.messages should contain (portReq)

            And("The actor unsubscribes from further updates to the bridge")
            VirtualTopologyActor.messages should contain (Unsubscribe(bridge.getId))
        }

        scenario("Pre-fetching a subscribed device doesn't request it again") {
            Given("A topology with a bridge")
            val bridgeReq = topologyActor.underlyingActor.bridge(bridge.getId)
            topologyActor ! bridgeReq
            VirtualTopologyActor.getAndClear() should contain (bridgeReq)

            When("Pre-fetching a port and the same bridge")
            val portReq = topologyActor.underlyingActor.port(port.getId)
            topologyActor ! List(portReq, bridgeReq)

            Then("The hook method is called with the pre-fetched devices")
            topologyActor.underlyingActor.topology should contain key port.getId
            topologyActor.underlyingActor.topology should contain key bridge.getId
            topologyActor.underlyingActor.topology.size should be (2)

            And("The VTA receives only the request for the port")
            VirtualTopologyActor.messages should be (List(portReq))
        }

        scenario("Changes to devices not in the topology are ignored") {
            Given("An empty topology")

            When("Devices not in the topology are received")
            topologyActor ! new SimBridge(
                bridge.getId, bridge.isAdminStateUp, bridge.getTunnelKey, null,
                null, null, null, null, null, null, null, null, null, null)

            Then("The hook method is not called")
            topologyActor.underlyingActor.topology should be (empty)

            And("The actor unsubscribes from further updates to the device")
            VirtualTopologyActor.messages should contain (Unsubscribe(bridge.getId))
        }
    }

    feature("A hook method is called when the specified devices are updated") {
        scenario("Devices in the topology are updated") {
            Given("A topology with a bridge")
            val bridgeReq = topologyActor.underlyingActor.bridge(bridge.getId)
            topologyActor ! bridgeReq
            topologyActor.underlyingActor.get[SimBridge](bridge.getId)
                                         .adminStateUp should be (true)

            When("Updating the bridge")

            topologyActor ! new SimBridge(
                bridge.getId, false, bridge.getTunnelKey, null,
                null, null, null, null, null, null, null, null, null, null)

            Then("The hook method is called with the updated bridge")
            topologyActor.underlyingActor.get[SimBridge](bridge.getId)
                                         .adminStateUp should be (false)
        }
    }
}
