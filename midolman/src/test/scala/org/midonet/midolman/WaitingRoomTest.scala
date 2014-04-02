/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman

import java.util.concurrent.TimeUnit
import scala.collection.mutable.ListBuffer

import org.scalatest.{Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

@RunWith(classOf[JUnitRunner])
class WaitingRoomTest extends FeatureSpec with Matchers {

    val to = TimeUnit.MILLISECONDS.toNanos(500)

    feature("Add a waiter") {

        scenario("Construction") {
            val wr = new WaitingRoom[Int](to)
            wr.timeout shouldEqual to
        }

        scenario("Construction with defaults") {
            val wr = new WaitingRoom[Int]()
            wr.timeout shouldEqual TimeUnit.SECONDS.toNanos(3)
        }

        scenario("Each waiter triggers a cleanup of timed out waiters") {
            val evictions = new ListBuffer[Int]()
            val wr = new WaitingRoom[Int](to)
            val w1 = 1
            val w2 = 2
            val w3 = 3

            // add elements and verify size
            wr.count should be (0)
            evictions ++= wr enter w1
            wr.count should be (1)
            evictions should have size 0 // no evictions
            wr enter w2
            wr.count should be (2)

            // duplicate elements
            evictions ++= wr enter w1
            evictions ++= wr enter w2
            wr.count should be (2) // now remain the same

            // wait for expirations
            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(to))

            // none should have happened since no elements were added
            wr.count should be (2)
            evictions should have size 0

            evictions ++= wr enter w3 // this expires, then adds
            wr.count should be (1)
            evictions shouldEqual List(w1, w2)
        }

        scenario("A waiter leaving before timing out is not expired") {
            val wr = new WaitingRoom[Int](to)
            List(1,2,3) foreach { wr enter _ }
            wr leave 2

            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(to))
            (wr enter 4) should (contain(1) and contain(3) and not contain(2))
        }
    }
}
