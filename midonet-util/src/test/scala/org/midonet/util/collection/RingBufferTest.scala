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
    var ring: RingBufferWithFactory[String] = null

    override def beforeEach() {
        ring = new RingBufferWithFactory[String](
            CAPACITY, null, i => i.toString)
    }

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
        intercept[IllegalStateException] { ring.put("overflow") }
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

    private def checkHeadAndTail(head: String, tail: String) {
        ring.head.get shouldBe head
        ring.peek.get shouldBe tail
    }

    feature("RingBuffer stores values") {
        scenario("holds capacity-1 values") {
            fillUp()
        }

        scenario("write pointer can roll over") {
            rollPointerHalfWay()
            fillUp()
        }

        scenario("creates elements with factory") {
            rollPointerHalfWay()
            for (value <- 0 until CAPACITY) {
                val v = ring.allocateAndPut()
                ring.take().get shouldBe v
                v shouldBe ((CAPACITY/2 + value) % CAPACITY).toString
            }
        }

        scenario("creates elements during initialization") {
            val head = CAPACITY/4 * 3
            val tail = CAPACITY/4
            val buffers = new RingBufferWithFactory[String](
                CAPACITY, null, i => i.toString, head, tail) {
                def buffers = ring
            }
            val iterator = buffers.iterator
            for (value <- 0 until CAPACITY/2) {
                val idx = CAPACITY/4 + value
                iterator.next shouldBe idx.toString
                buffers.buffers(idx) should not be null
            }
            for (i <- 0 until CAPACITY/4) {
                buffers.buffers(i) shouldBe null
            }
            for (i <- CAPACITY/4 * 3 until CAPACITY) {
                buffers.buffers(i) shouldBe null
            }
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

    feature("Ring buffer iterator") {
        scenario("Empty buffer") {
            ring.isEmpty shouldBe true
            ring.nonEmpty shouldBe false
            val iter = ring.iterator
            iter.hasNext shouldBe false
            iter.length shouldBe 0
        }

        scenario("Accessing outside the view") {
            ring.isEmpty shouldBe true
            ring.nonEmpty shouldBe false
            intercept[IndexOutOfBoundsException] {
                ring.iterator.next
            }
        }

        scenario("read without wrapping") {
            fillUp()
            val iter = ring.iterator
            iter.length shouldBe ring.length
            var pos = 1
            for (value <- iter) {
                value shouldBe pos.toString
                checkHeadAndTail((CAPACITY-1).toString, 1.toString)
                pos += 1
            }
        }

        scenario("read after wrapping") {
            rollPointerHalfWay()
            fillUp()
            var pos = 1
            for (value <- ring.iterator) {
                value shouldBe pos.toString
                checkHeadAndTail((CAPACITY-1).toString, 1.toString)
                pos += 1
            }
        }
    }
}
