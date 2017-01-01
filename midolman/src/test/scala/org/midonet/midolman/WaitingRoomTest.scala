/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman

import java.util.concurrent.TimeUnit

import scala.collection.mutable.ListBuffer

import org.scalatest.{FeatureSpec, Matchers}
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import org.midonet.midolman.simulation.PacketContext

@RunWith(classOf[JUnitRunner])
class WaitingRoomTest extends FeatureSpec with Matchers {

    val to = TimeUnit.MILLISECONDS.toNanos(500)

    feature("Add a waiter") {

        scenario("Construction") {
            val wr = new WaitingRoom(to)
            wr.timeout shouldEqual to
        }

        scenario("Construction with defaults") {
            val wr = new WaitingRoom()
            wr.timeout shouldEqual TimeUnit.SECONDS.toNanos(3)
        }

        def evictions(wr: WaitingRoom) = {
            val buf = new ListBuffer[PacketContext]()
            wr.doExpirations(buf += _)
            buf.toList
        }

        scenario("Each waiter triggers a cleanup of timed out waiters") {
            val wr = new WaitingRoom(to)
            val w1 = new PacketContext
            val w2 = new PacketContext
            val w3 = new PacketContext

            // add elements and verify size
            wr.count should be (0)
            wr enter w1
            wr.count should be (1)
            evictions(wr) should have size 0 // no evictions
            wr enter w2
            wr.count should be (2)

            // duplicate elements
            wr enter w1
            wr enter w2
            wr.count should be (2) // now remain the same

            // wait for expirations
            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(to))

            // none should have happened since no eviction was performed
            wr.count should be (2)

            wr enter w3
            wr.count should be (3)
            evictions(wr) shouldEqual List(w1, w2)
            wr.count should be (1)
        }

        scenario("A waiter leaving before timing out is not expired") {
            val wr = new WaitingRoom(to)
            val w1 = new PacketContext
            val w2 = new PacketContext
            val w3 = new PacketContext
            val w4 = new PacketContext

            List(w1, w2, w3) foreach wr.enter
            wr leave w2

            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(to))
            wr enter w4
            evictions(wr) should (contain(w1) and contain(w3) and not contain w2)
        }
    }

    feature("Reentry of objects") {
        scenario("A waiter is added, completes, and is added again.") {
            val wr = new WaitingRoom(to)
            val waiter = new PacketContext

            wr enter waiter
            wr leave waiter

            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(to))

            wr enter waiter
            wr.doExpirations(_.runs += 1)

            waiter.runs shouldBe 0
        }
    }
}
