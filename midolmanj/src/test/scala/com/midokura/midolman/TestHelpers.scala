/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman

import java.util.concurrent.TimeUnit
import util.Sudo
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers._

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

    def waitFor(totalTime: Long, waitTime: Long)(condition: => Boolean): Boolean = {
        val start = System.currentTimeMillis();
        val conditionResult = condition
        if (totalTime - (System.currentTimeMillis() - start) <= 0 || conditionResult) {
            return conditionResult
        }

        Thread.sleep(waitTime)

        waitFor(totalTime - (System.currentTimeMillis() - start), waitTime)(condition);
    }

    def assertSudoAccess(command: String) {
        assertThat("We can't seem to execute commands using sudo.",
            Sudo.sudoExec(command) == 0)
    }

    def assertSudoAccess() {
        assertSudoAccess("true")
    }

    def assertCommandFails(command: String) {
        val commandExitCode = Sudo.sudoExec(command)

        assertThat("Command \"%s\" should have exited.",
            commandExitCode, not(equalTo(0)))
    }

    def assertCommandSucceeds(command: String) {
        val commandExitCode = Sudo.sudoExec(command)

        assertThat("Command \"%s\" should have suceeded",
            commandExitCode, is(equalTo(0)))
    }
}
