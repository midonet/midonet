/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import scala.collection.JavaConversions._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.midolman.util.SimulationHelper
import org.midonet.odp.protos.mocks.MockOvsDatapathConnectionImpl
import org.midonet.odp.FlowMatch
import org.midonet.odp.flows.FlowKeyICMP
import org.midonet.sdn.flows.FlowManager

@RunWith(classOf[JUnitRunner])
class DatapathFlowTableConsistencyTestCase extends MidolmanTestCase
        with VMsBehindRouterFixture with SimulationHelper {
    private final val log =
        LoggerFactory.getLogger(classOf[DatapathFlowTableConsistencyTestCase])

    var datapath: MockOvsDatapathConnectionImpl = null
    var flowManager: FlowManager = null

    override def beforeTest() {
        super.beforeTest()

        flowManager = flowController().underlyingActor.flowManager
        datapath = dpConn().asInstanceOf[MockOvsDatapathConnectionImpl]

        arpVmToRouterAndCheckReply(vmPortNames(0), vmMacs(0), vmIps(0), routerIp, routerMac)
        arpVmToRouterAndCheckReply(vmPortNames(1), vmMacs(1), vmIps(1), routerIp, routerMac)
        findPingMatch should be (None)
    }

    private def findPingMatch: Option[FlowMatch] = {
        for (flowMatch <- datapath.flowsTable.keySet()) {
            for (flowKey <- flowMatch.getKeys) {
                flowKey match {
                    case icmp: FlowKeyICMP => return Option(flowMatch)
                    case _ =>
                }
            }
        }
        None
    }

    def testFlowGetMiss() {
        // cause flow to be installed.
        expectPacketAllowed(0, 1, icmpBetweenPorts)
        val pingMatch = findPingMatch
        pingMatch should not be (None)

        // remove flow, from the datapath
        pingMatch.foreach{datapath.flowsTable.remove(_)}
        findPingMatch should be (None)

        // call flowsGet(), need to wait IDLE_EXPIRATION / 2. That's 30 secs.
        Thread.sleep(32000)
        flowManager.checkFlowsExpiration()

        // check that flow was re-installed.
        findPingMatch should not be (None)
    }

    def testMultiplePacketIn() {
        // cause flow to be installed.
        expectPacketAllowed(0, 1, icmpBetweenPorts)
        val pingMatch = findPingMatch
        pingMatch should not be (None)

        // remove flow, from the datapath
        pingMatch.foreach{datapath.flowsTable.remove(_)}
        findPingMatch should be (None)

        // resend packet and check that the flow was not re-added
        expectPacketAllowed(0, 1, icmpBetweenPorts)
        findPingMatch should be (None)


        // wait 1 second, the threshold that the flow controller uses to decide
        // that it's time to reinstall the flow
        Thread.sleep(1000)

        // resend packet and check that the flow is re-added
        expectPacketAllowed(0, 1, icmpBetweenPorts)
        findPingMatch should not be (None)
    }
}
