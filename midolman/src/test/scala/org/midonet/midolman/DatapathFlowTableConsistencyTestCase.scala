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
import org.midonet.odp.flows.{FlowKeyICMPEcho, FlowKeyTCP, FlowKeyICMP}
import org.midonet.sdn.flows.FlowManager
import topology.BridgeManager

@RunWith(classOf[JUnitRunner])
class DatapathFlowTableConsistencyTestCase extends MidolmanTestCase
        with VMsBehindRouterFixture with SimulationHelper {
    private final val log =
        LoggerFactory.getLogger(classOf[DatapathFlowTableConsistencyTestCase])

    var datapath: MockOvsDatapathConnectionImpl = null
    var flowManager: FlowManager = null

    override def beforeTest() {
        BridgeManager.setMacPortExpiration(60000)
        super.beforeTest()

        flowManager = flowController().underlyingActor.flowManager
        datapath = dpConn().asInstanceOf[MockOvsDatapathConnectionImpl]

        arpVmToRouterAndCheckReply(vmPortNames(0), vmMacs(0), vmIps(0), routerIp, routerMac)
        arpVmToRouterAndCheckReply(vmPortNames(1), vmMacs(1), vmIps(1), routerIp, routerMac)
        findMatch[FlowKeyICMP] should be (None)
        findMatch[FlowKeyICMPEcho] should be (None)
        findMatch[FlowKeyTCP] should be (None)
    }

    private def findMatch[T](implicit m: Manifest[T]) : Option[FlowMatch] = {
        val klass = manifest.erasure.asInstanceOf[Class[T]]
        for (flowMatch <- datapath.flowsTable.keySet()) {
           for (flowKey <- flowMatch.getKeys) {
               if (klass.isInstance(flowKey)) {
                   return Option(flowMatch)
               }
           }
        }
        None
    }

    def testMultipleICMPPacketIn() {
        // flow will not be installed for ICMP echo req/reply
        // required to process them in userspace to support PING through NAT
        expectPacketAllowed(0, 1, icmpBetweenPorts)
        findMatch[FlowKeyICMPEcho] should be (None)
        findMatch[FlowKeyICMP] should be (None)

        // resend packet and check that the flow was not re-added
        expectPacketAllowed(0, 1, icmpBetweenPorts)
        findMatch[FlowKeyICMPEcho] should be (None)
        findMatch[FlowKeyICMP] should be (None)

        // wait 1 second, the threshold that the flow controller uses to decide
        // that it's time to reinstall the flow
        Thread.sleep(1000)

        // resend packet and check that the flow is still not added
        expectPacketAllowed(0, 1, icmpBetweenPorts)
        findMatch[FlowKeyICMPEcho] should be (None)
        findMatch[FlowKeyICMP] should be (None)
    }

    def testFlowGetMiss() {
        // cause flow to be installed.
        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(1),
            tcpBetweenPorts(_:Int, _:Int, 9009, 80))

        val tcpMatch = findMatch[FlowKeyTCP]
        tcpMatch should not be (None)

        // remove flow, from the datapath
        tcpMatch.foreach{datapath.flowsTable.remove(_)}
        findMatch[FlowKeyTCP] should be (None)

        // call flowsGet(), need to wait IDLE_EXPIRATION / 2. That's 30 secs.
        Thread.sleep(32000)
        flowManager.checkFlowsExpiration()

        // check that flow was re-installed.
        findMatch[FlowKeyTCP] should not be (None)
    }


    def testMultipleTCPPacketIn() {
        // cause flow to be installed.
        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(1),
            tcpBetweenPorts(_:Int, _:Int, 9009, 80))

        val pktMatch = findMatch[FlowKeyTCP]
        pktMatch should not be (None)

        // remove flow, from the datapath
        pktMatch.foreach{datapath.flowsTable.remove(_)}
        findMatch[FlowKeyTCP] should be (None)

        // resend packet and check that the flow was not re-added
        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(1),
            tcpBetweenPorts(_:Int, _:Int, 9009, 80))
        findMatch[FlowKeyTCP] should be (None)

        // wait 1 second, the threshold that the flow controller uses to decide
        // that it's time to reinstall the flow
        Thread.sleep(1000)

        // resend packet and check that the flow is re-added
        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(1),
            tcpBetweenPorts(_:Int, _:Int, 9009, 80))
        findMatch[FlowKeyTCP] should not be (None)
    }
}
