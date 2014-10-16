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

package org.midonet.util.collection

import org.junit.runner.RunWith
import org.scalatest.{FeatureSpec, BeforeAndAfterEach, Matchers}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class RingBufferTest extends FeatureSpec with BeforeAndAfterEach with Matchers {

    val CAPACITY = 8
    var ring: RingBuffer[String] = null

    override def beforeEach() { ring = new RingBuffer[String](CAPACITY, null) }

    private def rollPointerHalfWay() {
        ring.isEmpty should be (true)
        for (value <- 1 to (CAPACITY/2)) {
            ring.isFull should be (false)
            ring.put(value.toString)
            ring.take() should be (Some(value.toString))
            ring.isEmpty should be (true)
        }
    }

    private def doPutPeekAndTakeLoop(capacityMultiplier: Int) {
        ring.isEmpty should be (true)
        for (value <- 1 to (CAPACITY * capacityMultiplier)) {
            ring.isFull should be (false)
            ring.put(value.toString)
            ring.peek should be (Some(value.toString))
            ring.take() should be (Some(value.toString))
            ring.isEmpty should be (true)
        }
    }

    private def fillUp() {
        ring.isEmpty should be (true)
        for (value <- 1 to (CAPACITY-1)) {
            ring.isFull should be (false)
            ring.put(value.toString)
        }
        ring.peek should be (Some("1"))
        ring.isFull should be (true)
        intercept[IllegalArgumentException] { ring.put("overflow") }
    }

    private def readAll() {
        ring.isFull should be (true)
        for (value <- 1 to (CAPACITY-1)) {
            ring.isEmpty should be (false)
            ring.peek should be (Some(value.toString))
            ring.take() should be (Some(value.toString))
        }
        ring.peek should be (None)
        ring.take() should be (None)
        ring.isEmpty should be (true)
        ring.isFull should be (false)
    }

    feature("RingBuffer stores values") {
        scenario("holds capacity-1 values") {
            fillUp()
        }

        scenario("write pointer can roll over") {
            rollPointerHalfWay()
            fillUp()
        }
    }

    feature("RingBuffer reads values") {
        scenario("peeking") {
            ring.isEmpty should be (true)
            for (value <- 1 to (CAPACITY-1)) {
                ring.isFull should be (false)
                ring.put(value.toString)
                ring.peek should be (Some("1"))
            }
        }

        scenario("peek and take") {
            doPutPeekAndTakeLoop(4)
        }

        scenario("peek and take, rolling over") {
            rollPointerHalfWay()
            doPutPeekAndTakeLoop(4)
        }

        scenario("read the full ring") {
            fillUp()
            readAll()
        }

        scenario("read the full ring, rolling over") {
            rollPointerHalfWay()
            fillUp()
            readAll()
        }
    }
}
