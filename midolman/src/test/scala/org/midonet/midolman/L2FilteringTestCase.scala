/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import scala.concurrent.duration._
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.FlowController.WildcardFlowRemoved
import org.midonet.midolman.topology.FlowTagger
import org.midonet.midolman.rules.{RuleResult, Condition}
import org.midonet.packets._

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class L2FilteringTestCase extends MidolmanTestCase with VMsBehindRouterFixture {

    private final val log = LoggerFactory.getLogger(classOf[L2FilteringTestCase])

    def testAddAndModifyJumpChain() {

        // add rule that drops everything return flows
        log.info("creating inbound chain, assigning the chain to the bridge")
        val brInChain = newInboundChainOnBridge("brInFilter", bridge)
        // this is a chain that will be set as jump chain for brInChain
        val jumpChain = createChain("jumpRule", None)
        newLiteralRuleOnChain(brInChain, 1, new Condition(), RuleResult.Action.DROP)

        // send packet and except to see a drop flow
        triggerPacketIn(vmPortNames(0), icmpBetweenPorts(0, 3))
        expectPacketIn()
        ackWCAdded().actions.size should be (0)
        drainProbes()

        // add rule that accepts everything
        newLiteralRuleOnChain(brInChain, 1, new Condition(), RuleResult.Action.ACCEPT)
        ackWCRemoved()
        drainProbes()

        expectPacketAllowed(0, 3, icmpBetweenPorts)
        ackWCAdded()
        drainProbes()

        // add a rule that drops the packets from 0 to 3 in the jump chain
        val cond1 = new Condition()
        cond1.nwSrcIp = IPv4Addr.fromString(vmIps(0).toString).subnet()
        cond1.nwDstIp = IPv4Addr.fromString(vmIps(3).toString).subnet()
        val jumpRule = newLiteralRuleOnChain(jumpChain, 1, cond1, RuleResult.Action.DROP)
        newJumpRuleOnChain(brInChain, 1, cond1, jumpChain.getId)
        log.info("The flow should be invalidated")
        ackWCRemoved()

        log.info("sending a packet that should be dropped by jump rule")
        expectPacketDropped(0, 3, icmpBetweenPorts)
        ackWCAdded().actions.size should be (0)

        log.info("removing a rule from the jump rule itself (inner chain)")
        deleteRule(jumpRule.getId)
        ackWCRemoved()
        expectPacketAllowed(0, 3, icmpBetweenPorts)
        ackWCAdded().actions.size should (be > 0)

        log.info("adding back rule from the jump rule itself (inner chain)")
        newLiteralRuleOnChain(jumpChain, 1, cond1, RuleResult.Action.DROP)
        // expect invalidation
        ackWCRemoved()
        // expect that packet is dropped
        expectPacketDropped(0, 3, icmpBetweenPorts)
        ackWCAdded().actions.size should be (0)

    }

    def testFloodTagging() {
        val chain = newOutboundChainOnPort("p1OutChain", vmPorts(0))
        val cond = new Condition()

        drainProbes()

        newLiteralRuleOnChain(chain, 1, cond, RuleResult.Action.ACCEPT)
        clusterDataClient().bridgesUpdate(bridge)

        expectPacketAllowed(1, 2, icmpBetweenPorts)
        ackWCAdded()

        FlowController ! InvalidateFlowsByTag(
            FlowTagger.invalidateFlowsByDevice(chain.getId))

        ackWCRemoved()

    }

    def testV4ruleV6pktMatch() {
        val fromPort = 1
        val toPort = 2

        val chain = newInboundChainOnPort("p1InChain", vmPorts(fromPort))
        val cond = new Condition()
        cond.nwSrcIp = new IPv4Subnet(
                IPv4Addr.fromString(vmIps(fromPort).toString), 32)

        newLiteralRuleOnChain(chain, 1, cond, RuleResult.Action.DROP)

        expectPacketDropped(fromPort, toPort, icmpBetweenPorts)
        expectPacketAllowed(fromPort, toPort, ipv6BetweenPorts)
    }

    def testV6ruleV4pktMatch() {
        val fromPort = 1
        val toPort = 2
        val chain = newInboundChainOnPort("p1InChain", vmPorts(fromPort))
        val cond = new Condition()
        cond.nwSrcIp = new IPv6Subnet(v6VmIps(fromPort), 128)

        newLiteralRuleOnChain(chain, 1, cond, RuleResult.Action.DROP)

        expectPacketDropped(fromPort, toPort, ipv6BetweenPorts)
        expectPacketAllowed(fromPort, toPort, icmpBetweenPorts)
    }

    def test() {
        flowController().underlyingActor.flowManager.getNumWildcardFlows should be (vmPorts.size)

        log.info("populating the mac learning table with an arp request from each port")
        (vmPortNames, vmMacs, vmIps).zipped foreach {
            (name, mac, ip) => arpVmToRouterAndCheckReply(
                name, mac, ip, routerIp.getAddress, routerMac)
        }

        log.info("sending icmp echoes between every pair of ports")
        for (pair <- (0 to (vmPorts.size-1)).toList.combinations(2)) {
            expectPacketAllowed(pair.head, pair.last, icmpBetweenPorts)
            ackWCAdded()
            expectPacketAllowed(pair.last, pair.head, icmpBetweenPorts)
            ackWCAdded()
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
        cond2.nwSrcIp = new IPv4Subnet(
            IPv4Addr.fromString(vmIps(0).toString), 32)
        cond2.nwDstIp = new IPv4Subnet(
            IPv4Addr.fromString(vmIps(3).toString), 32)
        val rule2 = newLiteralRuleOnChain(brInChain, 2, cond2,
                                          RuleResult.Action.DROP)
        clusterDataClient().bridgesUpdate(bridge)

        log.info("checking that the creation of the chain invalidates all flows")
        for (pair <- (0 to (vmPorts.size-1)).toList.combinations(2)) {
            ackWCRemoved()
            ackWCRemoved()
        }
        flowController().underlyingActor.flowManager.getNumWildcardFlows should be (vmPorts.size)
        drainProbe(wflowAddedProbe)
        drainProbe(wflowRemovedProbe)

        log.info("sending a packet that should be dropped by rule 2")
        expectPacketDropped(0, 3, icmpBetweenPorts)
        ackWCAdded()
        log.info("sending a packet that should be allowed by rule 2")
        expectPacketAllowed(4, 1, icmpBetweenPorts)
        ackWCAdded()
        log.info("sending a packet that should be allowed by rule 2")
        expectPacketAllowed(0, 3, lldpBetweenPorts)
        ackWCAdded()

        log.info("adding a second rule: drop by mac from port4 to port1")
        val cond3 = new Condition()
        cond3.dlSrc = vmMacs(4)
        cond3.dlDst = vmMacs(1)
        val rule3 = newLiteralRuleOnChain(brInChain, 3, cond3,
                                          RuleResult.Action.DROP)

        1 to 3 foreach { _ => ackWCRemoved() }
        flowController().underlyingActor.flowManager.getNumWildcardFlows should be (vmPorts.size)

        log.info("sending two packets that should be dropped by rule 3")
        expectPacketDropped(4, 1, icmpBetweenPorts)
        ackWCAdded()
        expectPacketDropped(4, 1, lldpBetweenPorts)
        ackWCAdded()
        log.info("sending a packet that should be allowed by rules 2,3")
        expectPacketAllowed(4, 3, icmpBetweenPorts)
        ackWCAdded()
        log.info("sending an lldp packet that should be allowed by rules 2,3")
        expectPacketAllowed(4, 3, lldpBetweenPorts)
        ackWCAdded()

        log.info("adding a third rule: drop if ether-type == LLDP")
        val cond4 = new Condition()
        cond4.dlType = Unsigned.unsign(LLDP.ETHERTYPE)
        val rule4 = newLiteralRuleOnChain(brInChain, 4, cond4,
                                          RuleResult.Action.DROP)
        1 to 4 foreach { _ => ackWCRemoved() }
        flowController().underlyingActor.flowManager.getNumWildcardFlows should be (vmPorts.size)

        log.info("sending an lldp packet that should be dropped by rule 4")
        expectPacketDropped(4, 3, lldpBetweenPorts)
        ackWCAdded()
        log.info("sending an icmp packet that should be allowed by rule 4")
        expectPacketAllowed(4, 3, icmpBetweenPorts)
        ackWCAdded()

        log.info("deleting rule 4")
        clusterDataClient().rulesDelete(rule4.getId)
        ackWCRemoved()
        ackWCRemoved()
        flowController().underlyingActor.flowManager.getNumWildcardFlows should be (vmPorts.size)

        log.info("sending an lldp packet that should be allowed by the " +
                 "removal of rule 4")
        expectPacketAllowed(4, 3, lldpBetweenPorts)
        ackWCAdded()

        log.info("sending one packet that should be dropped with the same " +
                 "match as the return packet that will be sent later on")
        expectPacketDropped(4, 1, udpBetweenPorts)
        ackWCAdded()

        log.info("waiting for the return drop flows to timeout")
        // Flow expiration is checked every 10 seconds. The DROP flows should
        // expire in 3 seconds, but we wait 15 seconds for expiration to run.
        wflowRemovedProbe.within (15 seconds) {
            requestOfType[WildcardFlowRemoved](wflowRemovedProbe)
        }
        // The remaining (allowed) LLDP flow has an idle expiration of 60
        // seconds. We don't bother waiting for it because we're not testing
        // expiration here. The flow won't conflict with the following UDPs.

        drainProbes()
        log.info("sending a packet that should install a conntrack entry")
        expectPacketAllowed(1, 4, udpBetweenPorts)
        ackWCAdded()

        log.info("sending a return packet that should be accepted due to conntrack")
        expectPacketAllowed(4, 1, udpBetweenPorts)
    }
}
