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

import scala.collection.JavaConversions._
import scala.reflect.ClassTag
import scala.concurrent.duration._

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.FlowController.{AddWildcardFlow, WildcardFlowAdded}
import org.midonet.midolman.util.SimulationHelper
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.odp.FlowMatch
import org.midonet.odp.flows.{FlowKeyICMPEcho, FlowKeyTCP, FlowKeyICMP}
import org.midonet.odp.protos.MockOvsDatapathConnection
import org.midonet.sdn.flows.FlowManager

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class DatapathFlowTableConsistencyTestCase extends MidolmanTestCase
        with VMsBehindRouterFixture
        with SimulationHelper {

    var datapath: MockOvsDatapathConnection = null
    var flowManager: FlowManager = null

    override def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("bridge.mac_port_mapping_expire_millis", 60000)
        config.setProperty("midolman.idle_flow_tolerance_interval", 1)
        config
    }

    override def beforeTest() {

        super.beforeTest()

        flowManager = flowController().underlyingActor.flowManager
        datapath = dpConn().asInstanceOf[MockOvsDatapathConnection]

        arpVmToRouterAndCheckReply(vmPortNames(0), vmMacs(0), vmIps(0),
            routerIp.getAddress, routerMac)
        arpVmToRouterAndCheckReply(vmPortNames(1), vmMacs(1), vmIps(1),
            routerIp.getAddress, routerMac)
        findMatch[FlowKeyICMP] should be (None)
        findMatch[FlowKeyICMPEcho] should be (None)
        findMatch[FlowKeyTCP] should be (None)
    }

    private def findMatch[T](implicit m: ClassTag[T]) : Option[FlowMatch] = {
        for (flowMatch <- datapath.flowsTable.keySet()) {
           for (flowKey <- flowMatch.getKeys) {
               if (m.runtimeClass.isInstance(flowKey)) {
                   return Option(flowMatch)
               }
           }
        }
        None
    }

    def testMultipleICMPPacketInWithoutNat() {
        // flow is installed for ICMP echo req/reply only if the simulated
        // packet did not go through a NAT rule.
        drainProbes()
        expectPacketAllowed(0, 1, icmpBetweenPorts)
        findMatch[FlowKeyICMPEcho] should not be (None)
        findMatch[FlowKeyICMP] should not be (None)
        expectFlowAddedMessage()

        drainProbes()
        // resend packet and check that the flow was not re-added
        expectPacketAllowed(0, 1, icmpBetweenPorts)
        wflowAddedProbe.expectNoMsg(100 millis)

        findMatch[FlowKeyICMPEcho] should not be (None)
        findMatch[FlowKeyICMP] should not be (None)
    }

    def testFlowGetMiss() {
        // cause flow to be installed.
        expectPacketAllowed(vmPortNumbers(0)-3, vmPortNumbers(1)-3,
            tcpBetweenPorts(_:Int, _:Int, 9009, 80))

        requestOfType[WildcardFlowAdded](wflowAddedProbe)
        val tcpMatch = findMatch[FlowKeyTCP]
        tcpMatch should not be (None)
        flowManager.getNumDpFlows shouldBe 1

        // remove flow, from the datapath
        datapath.flowsTable remove tcpMatch.get
        findMatch[FlowKeyTCP] shouldBe None

        // instead of waiting for IDLE_EXPIRATION for 60 secs, expire the flow
        flowManager.oldestIdleFlow setLastUsedTimeMillis Long.MinValue

        flowManager.checkFlowsExpiration()
        flowManager.getNumDpFlows shouldBe 0
    }

    def testMultipleTCPPacketIn() {
        // cause flow to be installed.
        expectPacketAllowed(vmPortNumbers(0)-3, vmPortNumbers(1)-3,
            tcpBetweenPorts(_:Int, _:Int, 9009, 80))
        requestOfType[WildcardFlowAdded](wflowAddedProbe)

        val pktMatch = findMatch[FlowKeyTCP]
        pktMatch should not be (None)

        // remove flow, from the datapath
        pktMatch.foreach{datapath.flowsTable.remove(_)}
        findMatch[FlowKeyTCP] should be (None)

        drainProbes()
        // resend packet and check that the flow is re-added
        expectPacketAllowed(vmPortNumbers(0)-3, vmPortNumbers(1)-3,
            tcpBetweenPorts(_:Int, _:Int, 9009, 80))

        fishForRequestOfType[FlowController.FlowAdded](flowProbe())
        findMatch[FlowKeyTCP] should not be (None)
    }
}
