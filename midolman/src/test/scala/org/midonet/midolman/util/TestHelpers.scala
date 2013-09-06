/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.util

import collection.JavaConversions._
import collection.mutable
import java.util.concurrent.TimeUnit

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._
import org.junit.Assume.assumeTrue
import org.midonet.midolman.FlowController.{InvalidateFlowsByTag,
        WildcardFlowAdded, WildcardFlowRemoved}
import collection.mutable
import org.midonet.odp.flows.FlowAction
import org.midonet.packets._
import scala.collection.JavaConversions._
import org.midonet.midolman.FlowController.WildcardFlowAdded
import org.midonet.midolman.FlowController.InvalidateFlowsByTag
import org.midonet.midolman.FlowController.WildcardFlowRemoved


/**
 * Simple Scala object that should contain helpers methods to be used by a test
 * case,
 */

object TestHelpers {

    def waitFor(condition: => Boolean): Boolean = {
        waitFor(
            TimeUnit.SECONDS.toMillis(5),
            TimeUnit.MILLISECONDS.toMillis(500))(condition)
    }

    def waitFor(totalTime: Long, waitTime: Long)
               (condition: => Boolean): Boolean = {
        val start = System.currentTimeMillis();
        val conditionResult = condition
        if (totalTime - (System.currentTimeMillis() - start) <= 0 || 
                                conditionResult) {
            return conditionResult
        }

        Thread.sleep(waitTime)

        waitFor(totalTime - (System.currentTimeMillis() - start), 
                waitTime)(condition);
    }

    def assumeSudoAccess(command: String) {
        assumeTrue(Sudo.sudoExec(command) == 0)
    }

    def assumeSudoAccess() {
        assumeSudoAccess("true")
    }

    def assertCommandFails(command: String) {
        val commandExitCode = Sudo.sudoExec(command)

        assertThat("Command \"%s\" should have exited.".format(command),
            commandExitCode, not(equalTo(0)))
    }

    def assertCommandSucceeds(command: String) {
        val commandExitCode = Sudo.sudoExec(command)

        assertThat("Command \"%s\" should have succeeded".format(command),
            commandExitCode, is(equalTo(0)))
    }

    def getMatchFlowRemovedPacketPartialFunction = matchWCRemoved

    def partialMatch(acts: Seq[FlowAction[_]], targ: Seq[FlowAction[_]]) =
        targ.forall(acts.contains(_))

    def totalMatch(acts: Seq[FlowAction[_]], targ: Seq[FlowAction[_]]) =
        acts.size == targ.size && partialMatch(acts, targ)

    def matchWCAdded: PartialFunction[Any, Boolean] = {
        case msg: WildcardFlowAdded => true
        case _ => false
    }

    def matchWCRemoved: PartialFunction[Any, Boolean] = {
        case msg: WildcardFlowRemoved => true
        case _ => false
    }

    def matchActionsFlowAddedOrRemoved(
            flowActions: Seq[FlowAction[_]]): PartialFunction[Any, Boolean] = {
        case msg: WildcardFlowAdded =>
            totalMatch(msg.f.getActions, flowActions)
        case msg: WildcardFlowRemoved =>
            totalMatch(msg.f.getActions, flowActions)
        case _ => false
    }

    def partialMatchActionsFlowAddedOrRemoved(
            flowActions: Seq[FlowAction[_]]): PartialFunction[Any, Boolean] = {
        case msg: WildcardFlowAdded =>
            partialMatch(msg.f.getActions, flowActions)
        case msg: WildcardFlowRemoved =>
            partialMatch(msg.f.getActions, flowActions)
        case _ => false
    }

    def matchFlowTag(tag: AnyRef): PartialFunction[Any, Boolean] = {
        case msg: InvalidateFlowsByTag => msg.tag.equals(tag)
        case _ => false
    }

    def createUdpPacket(srcMac: String, srcIp: String, dstMac: String, dstIp: String): Ethernet = {
        Packets.udp(
            MAC.fromString(srcMac),
            MAC.fromString(dstMac),
            IPv4Addr.fromString(srcIp),
            IPv4Addr.fromString(dstIp),
            10, 11, "My UDP packet".getBytes)
    }
}
