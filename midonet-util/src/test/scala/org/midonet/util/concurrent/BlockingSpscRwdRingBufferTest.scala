/*
 * Copyright 2015 Midokura SARL
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

import java.util.concurrent.{TimeUnit, Executors, TimeoutException}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FeatureSpec, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Await, Future}

@RunWith(classOf[JUnitRunner])
class BlockingSpscRwdRingBufferTest extends FeatureSpec
                                            with BeforeAndAfterAll
                                            with BeforeAndAfterEach
                                            with Matchers {

    type RingBuffer[T] = BlockingSpscRwdRingBuffer[T]
    private val CAPACITY = 8 // should be a power of 2 for testing
    private var ring: RingBuffer[String] = null
    private val executor = Executors.newCachedThreadPool()
    private implicit val ec: ExecutionContext =
        ExecutionContext.fromExecutor(executor)

    override def afterAll() {
        executor.shutdownNow()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    override def beforeEach() { ring = new RingBuffer[String](CAPACITY) }

    private def rollPointerHalfWay() {
        ring.isEmpty shouldBe true
        for (value <- 1 to (CAPACITY/2)) {
            ring.isFull shouldBe false
            ring.add(value.toString)
            ring.poll().get.item should be (value.toString)
            ring.isEmpty shouldBe true
        }
    }

    private def doPutPeekAndTakeLoop(capacityMultiplier: Int) {
        ring.isEmpty shouldBe true
        for (value <- 1 to (CAPACITY * capacityMultiplier)) {
            ring.isFull shouldBe false
            ring.add(value.toString)
            ring.peek.get.item should be (value.toString)
            ring.poll().get.item should be (value.toString)
            ring.isEmpty shouldBe true
        }
    }

    private def fillUp() {
        ring.isEmpty shouldBe true
        val cur = ring.curSeqno
        for (value <- 1 to CAPACITY) {
            ring.isFull shouldBe false
            ring.add(value.toString)
        }
        ring.peek.get.seqno shouldBe cur
        ring.peek.get.item shouldBe "1"
        ring.isFull shouldBe true
        intercept[SpscRwdRingBuffer.BufferFullException]
            {ring.add("overflow")}
    }

    private def readAll() {
        ring.isFull shouldBe true
        val cur = ring.curSeqno
        for (value <- 1 to CAPACITY) {
            ring.isEmpty shouldBe false
            ring.peek.get.seqno shouldBe cur + value - 1
            ring.peek.get.item shouldBe value.toString
            ring.poll().get.item shouldBe value.toString
        }
        ring.peek shouldBe None
        ring.poll() shouldBe None
        ring.isEmpty shouldBe true
        ring.isFull shouldBe false
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
            ring.isEmpty shouldBe true
            for (value <- 1 to CAPACITY) {
                ring.isFull shouldBe false
                ring.add(value.toString)
                ring.peek.get.item shouldBe "1"
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

    feature("Blocking reads") {
        scenario("poll blocks on empty buffer") {
            val done = Future {ring.awaitPoll()}
            a [TimeoutException] shouldBe thrownBy(Await.ready(done, 2.seconds))
            ring.complete()
        }

        scenario("peek blocks on empty buffer") {
            val done = Future {ring.awaitPeek()}
            a [TimeoutException] shouldBe thrownBy(Await.ready(done, 2.seconds))
            ring.complete()
        }

        scenario("reader awakes on offer") {
            val done = Future {ring.awaitPoll()}
            Thread.sleep(2000)
            ring.offer("1")
            Await.result(done, 10.seconds).get.item shouldBe "1"
            ring.complete()
        }

        scenario("reader awakes on add") {
            val done = Future {ring.awaitPoll()}
            Thread.sleep(2000)
            ring.add("1")
            Await.result(done, 10.seconds).get.item shouldBe "1"
            ring.complete()
        }

        scenario("reader awakes on complete") {
            val done = Future {ring.awaitPoll()}
            Thread.sleep(2000)
            ring.complete()
            Await.result(done, 10.seconds).isEmpty shouldBe true
            ring.complete()
        }

        scenario("reader awakes on read pause") {
            val done = Future {ring.awaitPoll()}
            Thread.sleep(2000)
            ring.pauseRead()
            Await.result(done, 10.seconds).isEmpty shouldBe true
            ring.complete()
        }

        scenario("reader gets data from before complete") {
            ring.add("1")
            ring.complete()
            Await.result(Future {ring.awaitPoll()}, 10.seconds).get.item shouldBe "1"
            Await.result(Future {ring.awaitPoll()}, 10.seconds).isEmpty shouldBe true
            ring.complete()
        }

        scenario("reader does not get from before pause, until resume") {
            ring.add("1")
            ring.pauseRead()
            Await.result(Future {ring.awaitPoll()}, 10.seconds).isEmpty shouldBe true
            ring.resumeRead()
            Await.result(Future {ring.awaitPoll()}, 10.seconds).get.item shouldBe "1"
            ring.complete()
        }
    }
}
