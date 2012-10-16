/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.util

import java.util.concurrent.TimeUnit
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._
import org.junit.Assume.assumeTrue
import com.midokura.midolman.FlowController.WildcardFlowRemoved

/**
 * Simple Scala object that should contain helpers methods to be used by a test
 * case,
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 3/2/12
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
}
