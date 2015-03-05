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

package org.midonet.midolman

import java.util.concurrent.TimeUnit

import scala.Predef._
import scala.concurrent.duration.Duration

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.midonet.midolman.flows.FlowInvalidation
import org.midonet.midolman.simulation.PacketContext
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.FlowController._
import org.midonet.midolman.PacketWorkflow.PacketIn
import org.midonet.midolman.topology.VirtualTopologyActor.{BridgeRequest, PortRequest}
import org.midonet.midolman.topology.{LocalPortActive, VirtualTopologyActor}
import org.midonet.midolman.util.{Dilation, MidolmanTestCase}
import org.midonet.odp.FlowMatch.Field
import org.midonet.odp._
import org.midonet.packets.{IPv4Addr, MAC, Packets}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.MidonetEventually

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class FlowsExpirationTestCase extends MidolmanTestCase
                              with Dilation
                              with MidonetEventually {

    var datapath: Datapath = null

    var timeOutFlow: Long = 500
    var delayAsynchAddRemoveInDatapath: Long = timeOutFlow/3

    val ethPkt = Packets.udp(
        MAC.fromString("02:11:22:33:44:10"),
        MAC.fromString("02:11:22:33:44:11"),
        IPv4Addr.fromString("10.0.1.10"),
        IPv4Addr.fromString("10.0.1.11"),
        10, 11, "My UDP packet".getBytes)

    val ethPkt1 = Packets.udp(
        MAC.fromString("02:11:22:33:44:10"),
        MAC.fromString("02:11:22:33:44:12"),
        IPv4Addr.fromString("10.0.1.10"),
        IPv4Addr.fromString("10.0.1.11"),
        10, 11, "My UDP packet 2".getBytes)

    override def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        config.setProperty("datapath.max_flow_count", 3)
        config.setProperty("midolman.check_flow_expiration_interval", 10)
        config.setProperty("midolman.idle_flow_tolerance_interval", 1)
        config
    }

    override def beforeTest() {
        val myHost = newHost("myself", hostId)

        val bridge = newBridge("bridge")

        val port1 = newBridgePort(bridge)
        val port2 = newBridgePort(bridge)

        materializePort(port1, myHost, "port1")
        materializePort(port2, myHost, "port2")

        initializeDatapath() should not be null

        datapathEventsProbe.expectMsgType[DatapathController.DatapathReady]
            .datapath should not be null

        // Now disable sending messages to the DatapathController
        dpProbe().testActor ! "stop"
        dpProbe().expectMsg("stop")

        askAndAwait(VirtualTopologyActor, PortRequest(port1.getId))
        askAndAwait(VirtualTopologyActor, PortRequest(port2.getId))
        askAndAwait(VirtualTopologyActor, BridgeRequest(bridge.getId))

        requestOfType[LocalPortActive](portsProbe)
        requestOfType[LocalPortActive](portsProbe)

        drainProbes()
    }

    def testHardTimeExpiration() {
        triggerPacketIn("port1", ethPkt)

        val pktInMsg = fishForRequestOfType[PacketIn](packetInProbe)
        val wflow = wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded]).f
        flowInvalidator.scheduleInvalidationFor(FlowTagger.tagForDevice(pktInMsg.inputPort))
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])

        val flow = new Flow(FlowMatches.fromEthernetPacket(ethPkt))
        dpConn().futures.flowsCreate(datapath, flow)

        val newMatch = wflow.flowMatch
        newMatch.fieldUnused(Field.InputPortNumber)

        // we take a timestamp just before sending the AddWcF msg
        val timeAdded: Long = System.currentTimeMillis()
        val pktCtx = new PacketContext(0, null, newMatch)
        pktCtx.callbackExecutor = CallbackExecutor.Immediate
        pktCtx.lastInvalidation = FlowInvalidation.lastInvalidationEvent
        pktCtx.expiration = getDilatedTime(timeOutFlow).toInt
        flowProbe().testActor ! pktCtx

        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        // we have to wait because adding the flow into the dp is async
        dilatedSleep(delayAsynchAddRemoveInDatapath)

        dpConn().futures.flowsGet(datapath, flow.getMatch).get should not be null
        // we wait for the flow removed message that will be triggered because
        // the flow expired
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])

        // we take a time stamp just after getting the del event
        val timeDeleted: Long = System.currentTimeMillis()

        dilatedSleep(delayAsynchAddRemoveInDatapath)

        dpConn().futures.flowsGet(datapath, pktInMsg.dpMatch).get should be (null)

        // check that the flow expired in the correct time range. timeDeleted is
        // taken after flow deletion and timeAdded is taken before sending
        // the AddWcFlow msg, ignoring jitter, it is therefore guaranteed
        // that timeDeleted - timeAdded > realTimeDeleted - realTimeAdded
        (timeDeleted - timeAdded) should (be >= timeOutFlow)
        (timeDeleted - timeAdded) should (be < 2*timeOutFlow)

    }
}

