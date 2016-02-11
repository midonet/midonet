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
package org.midonet.netlink

import java.nio.ByteBuffer

import scala.collection.mutable.ListBuffer

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class BufferPoolTest extends FunSpec with Matchers {

    /** this mutable list is used to keep hard references to buffers taken from
     *  temporary pools so that they don't get GCed during the unit tests. */
    val buffers = new ListBuffer[ByteBuffer]()

    def refAndReleaseFrom(pool: BufferPool): (ByteBuffer) => Unit = {
        b =>
            buffers.synchronized {
                buffers += b
                pool release b
            }
    }

    describe("BufferPool") {
        it("can be created with valid parameters") {
            new BufferPool(10,20,1024).allocated shouldBe 10
            new BufferPool(2,2,1024).allocated shouldBe 2
            new BufferPool(1,2,1).allocated shouldBe 1
        }

        it("can't be created with invalid parameters") {
            List((2,1,128),(-3,2,128),(2,-3,128),(-2,-3,128),(1,2,-42)).foreach {
                case (l,h,s) =>
                  intercept[IllegalArgumentException] { new BufferPool(l,h,s) }
            }
        }

        it("should increase allocated buffers to max capicity to meet demand") {
            val pool = new BufferPool(10,20,128)
            checkAllocs(pool, 10, 10)
            (1 to 10) foreach { _ => buffers += pool.take }
            checkAllocs(pool, 10, 0)
            (1 to 10) foreach { _ => buffers += pool.take }
            checkAllocs(pool, 20, 0)
        }

        it("should allocate temporary GCed buffers when beyond max capacity") {
            val pool = new BufferPool(10,20,128)
            (1 to 3) foreach { _ =>
                (1 to 20) foreach { _ => buffers += pool.take }
                checkAllocs(pool, 20, 0)
            }
        }

        it("should count borrowed buffer correctly") {
            val pool = new BufferPool(10,10,128)
            pool.allocated shouldBe 10
            (10 to -10 by -1) foreach { i =>
                pool.available shouldBe i.max(0)
                buffers += pool.take
            }
        }

        it("should accept returning buffers") {
            val pool = new BufferPool(10,10,128)
            List(2,5,10) foreach { n =>
                val bufs = List.fill(n) { pool.take }
                pool.available shouldBe (10 - n)
                bufs foreach refAndReleaseFrom(pool)
                pool.available shouldBe 10
            }
        }

        describe("when returning buffers") {
            it("should recycle buffers") {
                val pool = new BufferPool(10,10,128)
                val bufs1 = List.fill(10) { pool.take }
                bufs1 foreach refAndReleaseFrom(pool)
                val bufs2 = List.fill(10) { pool.take }
                bufs1.toSet shouldBe bufs2.toSet
            }

            it("should not choke when returning a null buffer ref") {
                val pool = new BufferPool(10,10,128)
                pool release null
            }

            it("should not choke on foreign buffers returned incorrectly") {
                val pool = new BufferPool(10,20,128)
                (1 to 5) foreach { _ =>
                    pool release ByteBuffer.allocate(128)
                    pool.available shouldBe 10
                }
            }
        }

        describe("when serving multiple clients") {
            it("should stay in a consistent state") {
                val pool = new BufferPool(10,40,128)
                val clients = List.fill(4) {
                    new Thread(new Runnable() {
                        def run() {
                            (1 to 1000) foreach { _ =>
                                List.fill(5) { pool.take }
                                    .foreach(refAndReleaseFrom(pool))
                            }

                        }
                    })
                }
                clients foreach { _.start }
                clients foreach { _.join }
                pool.available() should be (pool.allocated())
                pool.available() should be <= 20
            }
        }

        def checkAllocs(pool: BufferPool, nAlloc: Int, nAvail: Int) {
            pool.allocated shouldBe nAlloc
            pool.available shouldBe nAvail
        }
    }
}
