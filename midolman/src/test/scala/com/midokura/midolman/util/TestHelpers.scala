/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.util

import java.util.concurrent.TimeUnit
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._
import org.junit.Assume.assumeTrue
import com.midokura.midolman.FlowController.{InvalidateFlowsByTag, WildcardFlowAdded, WildcardFlowRemoved}
import collection.mutable
import com.midokura.sdn.dp.flows.FlowAction
import com.midokura.packets.{Packets, MAC, IntIPv4, Ethernet}
import scala.collection.JavaConversions._


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

    def getMatchFlowRemovedPacketPartialFunction: PartialFunction[Any, Boolean] = {
        {
            case msg: WildcardFlowRemoved => true
            case _ => false
        }
    }

    def matchActionsFlowAddedOrRemoved(flowActions: mutable.Buffer[FlowAction[_]]):
    PartialFunction[Any, Boolean] = {
        {
            case msg: WildcardFlowAdded =>
                if(msg.f.getActions.equals(bufferAsJavaList[FlowAction[_]](flowActions)))
                    true
                else
                    false

            case msg: WildcardFlowRemoved =>
                if(msg.f.getActions.equals(bufferAsJavaList[FlowAction[_]](flowActions)))
                    true
                else
                    false
            case _ => false
        }
    }

    def matchFlowTag(tag: AnyRef):
    PartialFunction[Any, Boolean] = {
        {
            case msg: InvalidateFlowsByTag => msg.tag.equals(tag)
        }
    }

    def createUdpPacket(srcMac: String, srcIp: String, dstMac: String, dstIp: String): Ethernet = {
        Packets.udp(
            MAC.fromString(srcMac),
            MAC.fromString(dstMac),
            IntIPv4.fromString(srcIp),
            IntIPv4.fromString(dstIp),
            10, 11, "My UDP packet".getBytes)
    }
}
