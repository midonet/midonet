/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import akka.util.duration._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import com.midokura.midolman.FlowController.{WildcardFlowRemoved,
                                             WildcardFlowAdded}
import rules.{RuleResult, Condition}
import com.midokura.packets._
import util.SimulationHelper

@RunWith(classOf[JUnitRunner])
class L2FilteringTestCase extends MidolmanTestCase with VMsBehindRouterFixture
        with SimulationHelper {
    private final val log = LoggerFactory.getLogger(classOf[L2FilteringTestCase])

    def testAddAndModifyJumpChain() {
        drainProbes()
        log.info("creating inbound chain, assigning the chain to the bridge")
        val brInChain = newInboundChainOnBridge("brInFilter", bridge)

        // this is a chain that will be set as jump chain for brInChain
        val jumpChain = createChain("jumpRule", None)

        // add rule that allows return flows
        val cond = new Condition()
        cond.matchReturnFlow = true
        newLiteralRuleOnChain(brInChain, 1, cond, RuleResult.Action.ACCEPT)

        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(3), icmpBetweenPorts)

        drainProbes()
        drainProbe(packetsEventsProbe)
        drainProbe(flowEventsProbe)
        // add a rule that drops the packets from 0 to 3 in the jump chain
        val cond1 = new Condition()
        cond1.nwSrcIp = vmIps(0)
        cond1.nwDstIp = vmIps(3)
        val jumpRule = newLiteralRuleOnChain(jumpChain, 1, cond1, RuleResult.Action.DROP)
        newJumpRuleOnChain(brInChain, 1, cond1, jumpChain.getId)
        log.info("The flow should be invalidated")
        fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe)

        log.info("sending a packet that should be dropped by jump rule")
        expectPacketDropped(0, 3, icmpBetweenPorts)
        var flow = fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)
        flow.f.getActions.size() should be (0)

        log.info("removing a rule from the jump rule itself (inner chain)")
        deleteRule(jumpRule.getId)
        fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe)
        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(3), icmpBetweenPorts)
        flow = fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)
        flow.f.getActions.size() should (be > 0)

        log.info("adding back rule from the jump rule itself (inner chain)")
        newLiteralRuleOnChain(jumpChain, 1, cond1, RuleResult.Action.DROP)
        // expect invalidation
        fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe)
        // expect that packet is dropped
        expectPacketDropped(0, 3, icmpBetweenPorts)
        flow = fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)
        flow.f.getActions.size() should be (0)

    }

    def test() {
        flowController().underlyingActor.flowToTags.size should be === vmPorts.size

        log.info("populating the mac learning table with an arp request from each port")
        (vmPortNames, vmMacs, vmIps).zipped foreach {
            (name, mac, ip) => arpVmToRouterAndCheckReply(name, mac, ip, routerIp, routerMac)
        }

        log.info("sending icmp echoes between every pair of ports")
        for (pair <- (0 to (vmPorts.size-1)).toList.combinations(2)) {
            expectPacketAllowed(pair.head, pair.last, icmpBetweenPorts)
            requestOfType[WildcardFlowAdded](flowEventsProbe)
            expectPacketAllowed(pair.last, pair.head, icmpBetweenPorts)
            requestOfType[WildcardFlowAdded](flowEventsProbe)
        }
        drainProbes()

        log.info("creating chain")
        val brInChain = newInboundChainOnBridge("brInFilter", bridge)
        val cond1 = new Condition()
        cond1.matchReturnFlow = true
        val rule1 = newLiteralRuleOnChain(brInChain, 1, cond1,
                                          RuleResult.Action.ACCEPT)

        log.info("adding first rule: drop by ip from port0 to port3")
        val cond2 = new Condition()
        cond2.nwSrcIp = vmIps(0)
        cond2.nwDstIp = vmIps(3)
        val rule2 = newLiteralRuleOnChain(brInChain, 2, cond2,
                                          RuleResult.Action.DROP)
        clusterDataClient().bridgesUpdate(bridge)

        log.info("checking that the creation of the chain invalidates all flows")
        for (pair <- (0 to (vmPorts.size-1)).toList.combinations(2)) {
            fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe)
            fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe)
        }
        flowController().underlyingActor.flowToTags.size should be === vmPorts.size
        drainProbe(packetsEventsProbe)
        drainProbe(flowEventsProbe)

        log.info("sending a packet that should be dropped by rule 2")
        expectPacketDropped(0, 3, icmpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)
        log.info("sending a packet that should be allowed by rule 2")
        expectPacketAllowed(4, 1, icmpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)
        log.info("sending a packet that should be allowed by rule 2")
        expectPacketAllowed(0, 3, lldpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)

        log.info("adding a second rule: drop by mac from port4 to port1")
        val cond3 = new Condition()
        cond3.dlSrc = vmMacs(4)
        cond3.dlDst = vmMacs(1)
        val rule3 = newLiteralRuleOnChain(brInChain, 3, cond3,
                                          RuleResult.Action.DROP)

        1 to 3 foreach { _ => fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe) }
        flowController().underlyingActor.flowToTags.size should be === vmPorts.size

        log.info("sending two packets that should be dropped by rule 3")
        expectPacketDropped(4, 1, icmpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)
        expectPacketDropped(4, 1, lldpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)
        log.info("sending a packet that should be allowed by rules 2,3")
        expectPacketAllowed(4, 3, icmpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)
        log.info("sending an lldp packet that should be allowed by rules 2,3")
        expectPacketAllowed(4, 3, lldpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)

        log.info("adding a third rule: drop if ether-type == LLDP")
        val cond4 = new Condition()
        cond4.dlType = LLDP.ETHERTYPE
        val rule4 = newLiteralRuleOnChain(brInChain, 4, cond4,
                                          RuleResult.Action.DROP)
        1 to 4 foreach { _ => fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe) }
        flowController().underlyingActor.flowToTags.size should be === vmPorts.size

        log.info("sending an lldp packet that should be dropped by rule 4")
        expectPacketDropped(4, 3, lldpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)
        log.info("sending an icmp packet that should be allowed by rule 4")
        expectPacketAllowed(4, 3, icmpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)

        log.info("deleting rule 4")
        clusterDataClient().rulesDelete(rule4.getId)
        fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe)
        fishForRequestOfType[WildcardFlowRemoved](flowEventsProbe)
        flowController().underlyingActor.flowToTags.size should be === vmPorts.size

        log.info("sending an lldp packet that should be allowed by the " +
                 "removal of rule 4")
        expectPacketAllowed(4, 3, lldpBetweenPorts)
        requestOfType[WildcardFlowAdded](flowEventsProbe)

        log.info("sending two packets that should be dropped with the same " +
                 "match as the return packets that will be sent later on")
        expectPacketDropped(4, 1, udpBetweenPorts)
        requestOfType[WildcardFlowAdded](flowEventsProbe)
        expectPacketDropped(0, 3, udpBetweenPorts)
        requestOfType[WildcardFlowAdded](flowEventsProbe)

        log.info("waiting for the return drop flows to timeout")
        // Flow expiration is checked every 10 seconds. The DROP flows should
        // expire in 3 seconds, but we wait 11 seconds for expiration to run.
        flowEventsProbe.within (15 seconds) {
            requestOfType[WildcardFlowRemoved](flowEventsProbe)
            requestOfType[WildcardFlowRemoved](flowEventsProbe)
        }
        // The remaining (allowed) LLDP flow has an idle expiration of 60
        // seconds. We don't bother waiting for it because we're not testing
        // expiration here. The flow won't conflict with the following UDPs.

        drainProbe(flowEventsProbe)
        log.info("sending two packets that should install conntrack entries")
        expectPacketAllowed(1, 4, udpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)
        expectPacketAllowed(3, 0, udpBetweenPorts)
        fishForRequestOfType[WildcardFlowAdded](flowEventsProbe)

        log.info("sending two return packets that should be accepted due to " +
                 "conntrack")
        expectPacketAllowed(4, 1, udpBetweenPorts)
        expectPacketAllowed(0, 3, udpBetweenPorts)
    }
}
