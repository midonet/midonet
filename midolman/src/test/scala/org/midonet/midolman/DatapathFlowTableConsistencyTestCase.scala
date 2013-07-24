/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import scala.collection.JavaConversions._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.midolman.util.SimulationHelper
import org.midonet.odp.protos.MockOvsDatapathConnection
import org.midonet.odp.FlowMatch
import org.midonet.odp.flows.{FlowKeyICMPEcho, FlowKeyTCP, FlowKeyICMP}
import org.midonet.sdn.flows.FlowManager
import topology.BridgeManager
import org.midonet.midolman.FlowController.WildcardFlowAdded
import org.apache.commons.configuration.HierarchicalConfiguration

@RunWith(classOf[JUnitRunner])
class DatapathFlowTableConsistencyTestCase extends MidolmanTestCase
        with VMsBehindRouterFixture
        with SimulationHelper
        with VirtualConfigurationBuilders
{

    var datapath: MockOvsDatapathConnection = null
    var flowManager: FlowManager = null
    val flowExpiration: Long = 60000

    override def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("bridge.mac_port_mapping_expire_millis", 60000)
        config.setProperty("monitoring.enable_monitoring", "false")
        config.setProperty("midolman.idle_flow_tolerance_interval", 1)
        config
    }

    override def beforeTest() {

        super.beforeTest()

        flowManager = flowController().underlyingActor.flowManager
        datapath = dpConn().asInstanceOf[MockOvsDatapathConnection]

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

        drainProbe(dedupProbe())
        // resend packet and check that the flow was not re-added
        expectPacketAllowed(0, 1, icmpBetweenPorts)
        fishForRequestOfType[DeduplicationActor.ApplyFlow](dedupProbe())

        findMatch[FlowKeyICMPEcho] should be (None)
        findMatch[FlowKeyICMP] should be (None)
    }

    def testFlowGetMiss() {
        // cause flow to be installed.
        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(1),
            tcpBetweenPorts(_:Int, _:Int, 9009, 80))

        requestOfType[WildcardFlowAdded](wflowAddedProbe)
        val tcpMatch = findMatch[FlowKeyTCP]
        tcpMatch should not be (None)
        flowManager.getNumDpFlows should be (1)

        // remove flow, from the datapath
        tcpMatch.foreach{datapath.flowsTable.remove(_)}
        findMatch[FlowKeyTCP] should be (None)

        // call flowsGet(), need to wait IDLE_EXPIRATION. That's 60 secs.
        Thread.sleep(flowExpiration)
        flowManager.checkFlowsExpiration()

        flowManager.getNumDpFlows should be (0)
    }


    def testMultipleTCPPacketIn() {
        // cause flow to be installed.
        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(1),
            tcpBetweenPorts(_:Int, _:Int, 9009, 80))
        requestOfType[WildcardFlowAdded](wflowAddedProbe)

        val pktMatch = findMatch[FlowKeyTCP]
        pktMatch should not be (None)

        // remove flow, from the datapath
        pktMatch.foreach{datapath.flowsTable.remove(_)}
        findMatch[FlowKeyTCP] should be (None)

        drainProbe(flowProbe())
        // resend packet and check that the flow is re-added
        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(1),
            tcpBetweenPorts(_:Int, _:Int, 9009, 80))

        fishForRequestOfType[FlowController.FlowAdded](flowProbe())
        findMatch[FlowKeyTCP] should not be (None)
    }
}
