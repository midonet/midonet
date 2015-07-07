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

        def evictions[T](wr: WaitingRoom[T]) = {
            val buf = new ListBuffer[T]()
            wr.doExpirations(buf.+=)
            buf.toList
        }

        scenario("Each waiter triggers a cleanup of timed out waiters") {
            val wr = new WaitingRoom[Int](to)
            val w1 = 1
            val w2 = 2
            val w3 = 3

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
            val wr = new WaitingRoom[Int](to)
            List(1,2,3) foreach wr.enter
            wr leave 2

            Thread.sleep(TimeUnit.NANOSECONDS.toMillis(to))
            wr enter 4
            evictions(wr) should (contain(1) and contain(3) and not contain 2)
        }
    }
}
