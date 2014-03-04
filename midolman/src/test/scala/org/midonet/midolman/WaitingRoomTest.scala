/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman

import org.scalatest.{Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import scala.collection.mutable.ListBuffer
import java.util.concurrent.TimeUnit

@RunWith(classOf[JUnitRunner])
class WaitingRoomTest extends FeatureSpec with Matchers {

    val to = TimeUnit.MILLISECONDS.toNanos(500)

    feature("Add a waiter") {

        scenario("Construction") {
            val wr = new WaitingRoom[Int](_.hashCode, to)
            wr.timeout shouldEqual to
        }

        scenario("Construction with defaults") {
            val wr = new WaitingRoom[Int](_.hashCode)
            wr.timeout shouldEqual TimeUnit.SECONDS.toNanos(3)
        }

        scenario("Each waiter triggers a cleanup of timed out waiters") {
            val cb = new ListBuffer[Int]()
            val wr = new WaitingRoom[Int](x => cb ++= x, to)
            val w1 = 1
            val w2 = 2
            val w3 = 2

            // add elements and verify size
            wr.count should be (0)
            wr add w1
            wr.count should be (1)
            cb should have size 0 // no callbacks
            wr add w2
            wr.count should be (2)

            // duplicate elements
            wr add w1
            wr add w2
            wr.count should be (2) // now remain the same

            // wait for expirations
            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(to))

            // none should have happened since no elements were added
            wr.count should be (2)
            cb should have size 0

            wr add w3 // this expires, then adds
            wr.count should be (1)
            cb shouldEqual List(w1, w2)
        }

    }

}
