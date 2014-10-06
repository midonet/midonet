/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.simulation

import org.midonet.midolman.rules._
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.packets.IPAddr
import org.midonet.sdn.flows.WildcardMatch

import java.util.UUID
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfter, Matchers, Suite}
import org.scalatest.junit.JUnitRunner
import akka.actor.ActorSystem
import scala.collection.mutable

import scala.collection.JavaConverters._

private object ChainTest {
    protected val rejectRule = new LiteralRule(Condition.TRUE, Action.REJECT)
    protected val acceptRule = new LiteralRule(Condition.TRUE, Action.ACCEPT)
    protected val continueRule = new LiteralRule(Condition.FALSE, Action.ACCEPT)
}

@RunWith(classOf[JUnitRunner])
class ChainTest extends Suite
                with Matchers
                with BeforeAndAfter
                with BeforeAndAfterAll {
    import ChainTest._

    private implicit val actorSystem = ActorSystem("ChainTest")

    private var pktCtx: PacketContext = _
    private var pktMatch: WildcardMatch = _
    private val ownerId: UUID = UUID.randomUUID

    before {
        pktMatch = new WildcardMatch()
        pktMatch.setEthernetDestination("01:02:03:04:05:06")
        pktMatch.setNetworkDestination(IPAddr.fromString("1.2.3.4"))

        pktCtx = new PacketContext(Left(1), null, 0, None, pktMatch)
    }

    def testNullChain() {
        val res = applyChain(null)
        res.action should be (Action.ACCEPT)
    }

    def testEmptyChain() {
        val c = makeChain(Nil)
        applyChain(c).action should be (Action.ACCEPT)
    }

    def testAcceptRule() {
        val c = makeChain(List(acceptRule))
        applyChain(c).action should be (Action.ACCEPT)
    }

    def testRejectRule() {
        val c = makeChain(List(rejectRule))
        applyChain(c).action should be (Action.REJECT)
    }

    def testContinueUntilAccept() {
        val c = makeChain(List(continueRule, continueRule, acceptRule))
        applyChain(c).action should be (Action.ACCEPT)
    }

    def testContinueUntilReject() {
        val c = makeChain(List(continueRule, continueRule, rejectRule))
        applyChain(c).action should be (Action.REJECT)
    }

    def testContinueUntilEnd() {
        val c = makeChain(List(continueRule, continueRule, continueRule))
        applyChain(c).action should be (Action.ACCEPT)
    }

    def testAcceptThenReject() {
        val c = makeChain(List(acceptRule, rejectRule))
        applyChain(c).action should be (Action.ACCEPT)
    }

    def testRejectThenAccept() {
        val c = makeChain(List(rejectRule, acceptRule))
        applyChain(c).action should be (Action.REJECT)
    }

    def testJumpToAccept() {
        val acceptChain = makeChain(List(acceptRule))
        val c = makeChain(List(makeJumpRule(acceptChain)),
                          List(acceptChain))
        applyChain(c).action should be (Action.ACCEPT)
    }

    def testJumpToReject() {
        val rejectChain = makeChain(List(rejectRule))
        val c = makeChain(List(makeJumpRule(rejectChain)),
                          List(rejectChain))
        applyChain(c).action should be (Action.REJECT)
    }

    def testDoubleJumpToAccept() {
        val acceptChain = makeChain(List(acceptRule))
        val innerJumpChain = makeChain(List(makeJumpRule(acceptChain)),
                                       List(acceptChain))
        val outerJumpChain = makeChain(List(makeJumpRule(innerJumpChain)),
                                       List(innerJumpChain))
        applyChain(outerJumpChain).action should be (Action.ACCEPT)
    }

    def testDoubleJumpToReject() {
        val rejectChain = makeChain(List(rejectRule))
        val innerJumpChain = makeChain(List(makeJumpRule(rejectChain)),
                                       List(rejectChain))
        val outerJumpChain = makeChain(List(makeJumpRule(innerJumpChain)),
                                       List(innerJumpChain))
        applyChain(outerJumpChain).action should be (Action.REJECT)
    }

    /*
     * outerJumpChain
     *   innerJumpToContinueChain
     *     Continue
     *   innerJumpToAcceptChain
     *     Accept <-- Stop here
     */
    def testBacktrackToAccept() {
        val continueChain = makeChain(List(continueRule))
        val innerJumpToContinueChain =
            makeChain(List(makeJumpRule(continueChain)), List(continueChain))
        val acceptChain = makeChain(List(acceptRule))
        val innerJumpToAcceptChain =
            makeChain(List(makeJumpRule(acceptChain)), List(acceptChain))
        val outerJumpChain =
            makeChain(List(makeJumpRule(innerJumpToContinueChain),
                           makeJumpRule(innerJumpToAcceptChain),
                           rejectRule), // Shouldn't make it to this rule.
                      List(innerJumpToContinueChain, innerJumpToAcceptChain))
        applyChain(outerJumpChain).action should be (Action.ACCEPT)
    }

    /*
     * outerJumpChain
     *   Juwp to innerJumpToContinueChain:
     *     Continue
     *   Jump to innerJumpToRejectChain:
     *     Reject <-- Stop here
     */
    def testBacktrackToReject() {
        val continueChain = makeChain(List(continueRule))
        val innerJumpToContinueChain =
            makeChain(List(makeJumpRule(continueChain)), List(continueChain))
        val rejectChain = makeChain(List(rejectRule))
        val innerJumpToRejectChain =
            makeChain(List(makeJumpRule(rejectChain)), List(rejectChain))
        val outerJumpChain =
            makeChain(List(makeJumpRule(innerJumpToContinueChain),
                           makeJumpRule(innerJumpToRejectChain),
                           acceptRule), // Shouldn't make it to this rule.
                      List(innerJumpToContinueChain, innerJumpToRejectChain))
        applyChain(outerJumpChain).action should be (Action.REJECT)
    }

    /*
     * innerAndOuterChain:
     *   Jump to middleChain:
     *     Jump to innerAndOuterChain <-- Loop detected.
     *     Accept <-- Stop here
     *   Reject <-- Never reached.
     */
    def testLoopThenAccept() {
        // Can't use makeChain because we need to add a jump target.
        val jumpTargetMap = mutable.Map[UUID, Chain]()
        val chainId = UUID.randomUUID
        val name = "Chain-" + chainId
        val innerAndOuterChain = new Chain(chainId,
                                           List[Rule](rejectRule).asJava,
                                           jumpTargetMap, name)
        val middleChain = makeChain(List(makeJumpRule(innerAndOuterChain),
                                         acceptRule),
                                    List(innerAndOuterChain))

        // Add a jump from innerAndOuterChain to middleChain.
        innerAndOuterChain.getRules.add(0, makeJumpRule(middleChain))
        jumpTargetMap(middleChain.id) = middleChain

        applyChain(innerAndOuterChain).action should be (Action.ACCEPT)
    }

    /*
     * innerAndOuterChain:
     *   Jump to middleChain:
     *     Jump to innerAndOuterChain <-- Loop detected.
     *     Reject <-- Stop here
     *   Accept <-- Never reached.
     */
    def testLoopThenReject() {
        // Can't use makeChain because we need to add a jump target.
        val jumpTargetMap = mutable.Map[UUID, Chain]()
        val chainId = UUID.randomUUID
        val name = "Chain-" + chainId
        val innerAndOuterChain = new Chain(chainId,
                                           List[Rule](acceptRule).asJava,
                                           jumpTargetMap, name)
        val middleChain = makeChain(List(makeJumpRule(innerAndOuterChain),
                                         rejectRule),
                                    List(innerAndOuterChain))

        // Add a jump from innerAndOuterChain to middleChain.
        innerAndOuterChain.getRules.add(0, makeJumpRule(middleChain))
        jumpTargetMap(middleChain.id) = middleChain

        applyChain(innerAndOuterChain).action should be (Action.REJECT)
    }

    private def applyChain(c: Chain) =
        Chain.apply(c, pktCtx, ownerId, false)

    private def makeChain(rules: List[Rule],
                          jumpTargets: List[Chain] = Nil): Chain = {
        val chainId = UUID.randomUUID
        val jumpTargetMap = jumpTargets.map(c => (c.id, c)).toMap
        val name = "Chain-" + chainId.toString
        rules.foreach(_.chainId = chainId)
        new Chain(chainId, rules.asJava, jumpTargetMap, name)
    }

    private def makeJumpRule(target: Chain) =
        new JumpRule(Condition.TRUE, target.id, target.name, null, 0)
}
