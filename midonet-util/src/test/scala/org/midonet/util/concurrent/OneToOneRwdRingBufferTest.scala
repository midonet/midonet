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

package org.midonet.util.concurrent

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterEach, FeatureSpec, Matchers}

@RunWith(classOf[JUnitRunner])
class OneToOneRwdRingBufferTest extends FeatureSpec with BeforeAndAfterEach with Matchers {

    type RingBuffer[T] = OneToOneRwdRingBuffer[T]
    val CAPACITY = 8 // should be a power of 2 for testing
    var ring: RingBuffer[String] = null

    override def beforeEach() { ring = new RingBuffer[String](CAPACITY) }

    private def rollPointerHalfWay() {
        ring.isEmpty should be (true)
        for (value <- 1 to (CAPACITY/2)) {
            ring.isFull should be (false)
            ring.add(value.toString)
            ring.poll().get._2 should be (value.toString)
            ring.isEmpty should be (true)
        }
    }

    private def doPutPeekAndTakeLoop(capacityMultiplier: Int) {
        ring.isEmpty should be (true)
        for (value <- 1 to (CAPACITY * capacityMultiplier)) {
            ring.isFull should be (false)
            ring.add(value.toString)
            ring.peek().get._2 should be (value.toString)
            ring.poll().get._2 should be (value.toString)
            ring.isEmpty should be (true)
        }
    }

    private def fillUp() {
        ring.isEmpty should be (true)
        val cur = ring.curSeqno
        for (value <- 1 to (CAPACITY)) {
            ring.isFull should be (false)
            ring.add(value.toString)
        }
        ring.peek().get._1 should be (cur)
        ring.peek().get._2 should be ("1")
        ring.isFull should be (true)
        intercept[OneToOneRwdRingBuffer.BufferFullException]
            {ring.add("overflow")}
    }

    private def readAll() {
        ring.isFull should be (true)
        val cur = ring.curSeqno
        for (value <- 1 to (CAPACITY)) {
            ring.isEmpty should be (false)
            ring.peek().get._1 should be (cur + value - 1)
            ring.peek().get._2 should be (value.toString)
            ring.poll().get._2 should be (value.toString)
        }
        ring.peek should be (None)
        ring.poll() should be (None)
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
            for (value <- 1 to (CAPACITY)) {
                ring.isFull should be (false)
                ring.add(value.toString)
                ring.peek().get._2 should be ("1")
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

        scenario("read the full ring, rewind and read again") {
            fillUp()
            readAll()
            ring.rewind(0)
            readAll()
        }
    }
}
