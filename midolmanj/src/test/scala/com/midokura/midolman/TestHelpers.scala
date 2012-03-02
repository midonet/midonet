/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman

import java.util.concurrent.TimeUnit

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
}
