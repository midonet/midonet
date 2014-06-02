/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import java.util.UUID
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory
import org.midonet.midolman.rules.{RuleResult, Condition}
import org.midonet.midolman.util.SimulationHelper
import org.midonet.cluster.data.{Chain, Rule}
import org.midonet.packets._
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.util.MidolmanTestCase
import org.midonet.util.Range

@Category(Array(classOf[SimulationTests]))
@RunWith(classOf[JUnitRunner])
class ChainModificationTestCase extends MidolmanTestCase with VMsBehindRouterFixture
        with SimulationHelper {
    private final val log =
        LoggerFactory.getLogger(classOf[ChainModificationTestCase])

    private var chainRules = List[UUID]()
    private var chain: Chain = null

    override def beforeTest() {
        super.beforeTest()

        chain = newInboundChainOnBridge("brInFilter", bridge)
        var r: Rule[_,_] = null
        /*
         * Chain config:
         *   0: tcp dst port 80 => ACCEPT
         *   1: tcp src port 9009 => DROP
         *   2: tcp src port 3456 => ACCEPT
         *   3: tcp dst port 81 => DROP
         */
        r = newTcpDstRuleOnChain(chain, 1, 81, RuleResult.Action.DROP)
        chainRules = r.getId :: chainRules

        val tcpCond2 = newCondition(nwProto = Some(TCP.PROTOCOL_NUMBER),
                                    tpSrc = Some(3456))
        r = newLiteralRuleOnChain(chain, 1, tcpCond2, RuleResult.Action.ACCEPT)
        chainRules = r.getId :: chainRules

        val tcpCond3 = newCondition(nwProto = Some(TCP.PROTOCOL_NUMBER),
                                    tpSrc = Some(9009))
        r = newLiteralRuleOnChain(chain, 1, tcpCond3, RuleResult.Action.DROP)
        chainRules = r.getId :: chainRules

        r = newTcpDstRuleOnChain(chain, 1, 80, RuleResult.Action.ACCEPT)
        chainRules = r.getId :: chainRules
    }

    def testMiddleRuleDelete() {
        expectPacketDropped(vmPortNumbers(0)-3, vmPortNumbers(3)-3,
                            tcpBetweenPorts(_:Int, _:Int, 9009, 22))
        expectPacketAllowed(vmPortNumbers(0)-3, vmPortNumbers(3)-3,
                            tcpBetweenPorts(_:Int, _:Int, 9009, 80))
        expectPacketAllowed(vmPortNumbers(0)-3, vmPortNumbers(3)-3,
                            tcpBetweenPorts(_:Int, _:Int, 3456, 81))
        drainProbes()
        deleteRule(chainRules.apply(0))
        fishForRequestOfType[InvalidateFlowsByTag](flowProbe())

        expectPacketDropped(vmPortNumbers(0)-3, vmPortNumbers(3)-3,
                            tcpBetweenPorts(_:Int, _:Int, 9009, 80))
        expectPacketAllowed(vmPortNumbers(0)-3, vmPortNumbers(3)-3,
                            tcpBetweenPorts(_:Int, _:Int, 3456, 81))

        drainProbes()
        deleteRule(chainRules.apply(1))
        fishForRequestOfType[InvalidateFlowsByTag](flowProbe())

        expectPacketAllowed(vmPortNumbers(0)-3, vmPortNumbers(3)-3,
                            tcpBetweenPorts(_:Int, _:Int, 9009, 80))
        expectPacketAllowed(vmPortNumbers(0)-3, vmPortNumbers(3)-3,
                            tcpBetweenPorts(_:Int, _:Int, 3456, 81))
        expectPacketAllowed(vmPortNumbers(0)-3, vmPortNumbers(3)-3,
                            tcpBetweenPorts(_:Int, _:Int, 6000, 22))

        drainProbes()
        deleteRule(chainRules.apply(2))
        fishForRequestOfType[InvalidateFlowsByTag](flowProbe())

        expectPacketDropped(vmPortNumbers(0)-3, vmPortNumbers(3)-3,
            tcpBetweenPorts(_:Int, _:Int, 3456, 81))
    }

    def testLastRuleDelete() {
        expectPacketDropped(vmPortNumbers(0)-3, vmPortNumbers(3)-3,
                            tcpBetweenPorts(_:Int, _:Int, 3000, 81))
        drainProbes()

        deleteRule(chainRules.apply(3))
        fishForRequestOfType[InvalidateFlowsByTag](flowProbe())

        expectPacketAllowed(vmPortNumbers(0)-3, vmPortNumbers(3)-3,
                            tcpBetweenPorts(_:Int, _:Int, 3000, 81))
    }

    def testMiddleRuleInsert() {
        expectPacketAllowed(vmPortNumbers(0)-3, vmPortNumbers(3)-3,
                            tcpBetweenPorts(_:Int, _:Int, 3456, 80))
        drainProbes()

        val tcpCond = newCondition(nwProto = Some(TCP.PROTOCOL_NUMBER),
                                   tpSrc = Some(3456))
        newLiteralRuleOnChain(chain, 1, tcpCond, RuleResult.Action.DROP)
        fishForRequestOfType[InvalidateFlowsByTag](flowProbe())

        expectPacketDropped(vmPortNumbers(0)-3, vmPortNumbers(3)-3,
                            tcpBetweenPorts(_:Int, _:Int, 3456, 80))
        expectPacketAllowed(vmPortNumbers(0)-3, vmPortNumbers(3)-3,
                            tcpBetweenPorts(_:Int, _:Int, 6543, 80))
    }

    def testLastRuleInsert() {
        expectPacketAllowed(vmPortNumbers(0)-3, vmPortNumbers(3)-3,
                            tcpBetweenPorts(_:Int, _:Int, 7000, 22))
        drainProbes()

        val tcpCond = newCondition(nwProto = Some(TCP.PROTOCOL_NUMBER),
                                   tpSrc = Some(7000))
        newLiteralRuleOnChain(chain, 5, tcpCond, RuleResult.Action.DROP)
        fishForRequestOfType[InvalidateFlowsByTag](flowProbe())

        expectPacketDropped(vmPortNumbers(0)-3, vmPortNumbers(3)-3,
                            tcpBetweenPorts(_:Int, _:Int, 7000, 22))
    }
}
