/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import java.util.UUID
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import com.midokura.midolman.rules.{RuleResult, Condition}
import com.midokura.midolman.util.SimulationHelper
import com.midokura.midonet.cluster.data.{Chain, Rule}
import com.midokura.packets._
import com.midokura.midolman.FlowController.InvalidateFlowsByTag

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
        val tcpCond = new Condition()
        tcpCond.nwProto = Byte.box(TCP.PROTOCOL_NUMBER)
        tcpCond.tpDstStart = 81
        tcpCond.tpDstEnd = 81
        r = newLiteralRuleOnChain(chain, 1, tcpCond, RuleResult.Action.DROP)
        chainRules = r.getId :: chainRules

        val tcpCond2 = new Condition()
        tcpCond2.nwProto = Byte.box(TCP.PROTOCOL_NUMBER)
        tcpCond2.tpSrcStart = 3456
        tcpCond2.tpSrcEnd = 3456
        r = newLiteralRuleOnChain(chain, 1, tcpCond2, RuleResult.Action.ACCEPT)
        chainRules = r.getId :: chainRules

        val tcpCond3 = new Condition()
        tcpCond3.nwProto = Byte.box(TCP.PROTOCOL_NUMBER)
        tcpCond3.tpSrcStart = 9009
        tcpCond3.tpSrcEnd = 9009
        r = newLiteralRuleOnChain(chain, 1, tcpCond3, RuleResult.Action.DROP)
        chainRules = r.getId :: chainRules

        val tcpCond4 = new Condition()
        tcpCond4.nwProto = Byte.box(TCP.PROTOCOL_NUMBER)
        tcpCond4.tpDstStart = 80
        tcpCond4.tpDstEnd = 80
        r = newLiteralRuleOnChain(chain, 1, tcpCond4, RuleResult.Action.ACCEPT)
        chainRules = r.getId :: chainRules
    }

    def testMiddleRuleDelete() {
        expectPacketDropped(vmPortNumbers(0), vmPortNumbers(3),
                            tcpBetweenPorts(_:Int, _:Int, 9009, 22))
        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(3),
                            tcpBetweenPorts(_:Int, _:Int, 9009, 80))
        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(3),
                            tcpBetweenPorts(_:Int, _:Int, 3456, 81))
        drainProbes()
        deleteRule(chainRules.apply(0))
        requestOfType[InvalidateFlowsByTag](flowProbe())

        expectPacketDropped(vmPortNumbers(0), vmPortNumbers(3),
                            tcpBetweenPorts(_:Int, _:Int, 9009, 80))
        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(3),
                            tcpBetweenPorts(_:Int, _:Int, 3456, 81))

        drainProbes()
        deleteRule(chainRules.apply(1))
        requestOfType[InvalidateFlowsByTag](flowProbe())

        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(3),
                            tcpBetweenPorts(_:Int, _:Int, 9009, 80))
        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(3),
                            tcpBetweenPorts(_:Int, _:Int, 3456, 81))
        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(3),
                            tcpBetweenPorts(_:Int, _:Int, 6000, 22))

        drainProbes()
        deleteRule(chainRules.apply(2))
        requestOfType[InvalidateFlowsByTag](flowProbe())

        expectPacketDropped(vmPortNumbers(0), vmPortNumbers(3),
            tcpBetweenPorts(_:Int, _:Int, 3456, 81))
    }

    def testLastRuleDelete() {
        expectPacketDropped(vmPortNumbers(0), vmPortNumbers(3),
                            tcpBetweenPorts(_:Int, _:Int, 3000, 81))
        drainProbes()

        deleteRule(chainRules.apply(3))
        requestOfType[InvalidateFlowsByTag](flowProbe())

        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(3),
                            tcpBetweenPorts(_:Int, _:Int, 3000, 81))
    }

    def testMiddleRuleInsert() {
        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(3),
                            tcpBetweenPorts(_:Int, _:Int, 3456, 80))
        drainProbes()

        val tcpCond = new Condition()
        tcpCond.nwProto = Byte.box(TCP.PROTOCOL_NUMBER)
        tcpCond.tpSrcStart = 3456
        tcpCond.tpSrcEnd = 3456
        newLiteralRuleOnChain(chain, 1, tcpCond, RuleResult.Action.DROP)
        requestOfType[InvalidateFlowsByTag](flowProbe())

        expectPacketDropped(vmPortNumbers(0), vmPortNumbers(3),
                            tcpBetweenPorts(_:Int, _:Int, 3456, 80))
        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(3),
                            tcpBetweenPorts(_:Int, _:Int, 6543, 80))
    }

    def testLastRuleInsert() {
        expectPacketAllowed(vmPortNumbers(0), vmPortNumbers(3),
                            tcpBetweenPorts(_:Int, _:Int, 7000, 22))
        drainProbes()

        val tcpCond = new Condition()
        tcpCond.nwProto = Byte.box(TCP.PROTOCOL_NUMBER)
        tcpCond.tpSrcStart = 7000
        tcpCond.tpSrcEnd = 7000
        newLiteralRuleOnChain(chain, 5, tcpCond, RuleResult.Action.DROP)
        requestOfType[InvalidateFlowsByTag](flowProbe())

        expectPacketDropped(vmPortNumbers(0), vmPortNumbers(3),
                            tcpBetweenPorts(_:Int, _:Int, 7000, 22))
    }
}
