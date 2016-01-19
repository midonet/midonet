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
import java.util.{Set => JSet}
import scala.util.Try

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.netlink.exceptions.NetlinkException;

@RunWith(classOf[JUnitRunner])
class NetlinkRequestTest extends FunSpec with Matchers {

    describe("NetlinkRequest") {

        it("releases its request bytebuffer on first call only") {
            val cb = new InspectableCallback
            val buf = ByteBuffer allocate 256
            val req = requestFor(cb, buf)
            req.releaseRequestPayload shouldBe buf
            req.releaseRequestPayload shouldBe null
            req.releaseRequestPayload shouldBe null
        }

        describe("Can be turned into a runnable") {

            it("activates the onSuccess() callback if made from successful()") {
                val cb = new InspectableCallback
                val req = requestFor(cb)
                req addAnswerFragment (ByteBuffer allocate 256)
                val runnable = req.successful()

                (0 to 3) foreach { _ =>
                    runnable.run
                    cb.calls should be (1,0)
                }
            }

            it("activates the onTimeout() callback if made from expired()") {
                val cb = new InspectableCallback
                val req = requestFor(cb)
                val runnable = req.expired()

                (0 to 3) foreach { _ =>
                    runnable.run
                    cb.calls should be (0,1)
                }
            }

            it("activates the onError() callback if made from failed()") {
                val cb = new InspectableCallback
                val req = requestFor(cb)
                val runnable = req failed new NetlinkException(10, "foo", 0)

                (0 to 3) foreach { _ =>
                    runnable.run
                    cb.calls should be (0,1)
                }
            }

        }

        it("can be turned into several runnable, but runs once only (case 1)") {
            val cb = new InspectableCallback
            val req = requestFor(cb)
            req addAnswerFragment (ByteBuffer allocate 256)
            val runnable1 = req.successful()
            val runnable2 = req.expired()
            val runnable3 = req failed new NetlinkException(10, "foo", 0)

            runnable1.run
            cb.calls should be (1,0)
            runnable2.run
            cb.calls should be (1,0)
            runnable3.run
            cb.calls should be (1,0)
        }

        it("can be turned into several runnable, but runs once only (case 2)") {
            val cb = new InspectableCallback
            val req = requestFor(cb)
            req addAnswerFragment (ByteBuffer allocate 256)
            val runnable2 = req.expired()
            val runnable3 = req failed new NetlinkException(10, "foo", 0)
            val runnable1 = req.successful()

            runnable2.run
            cb.calls should be (0,1)
            runnable3.run
            cb.calls should be (0,1)
            runnable1.run
            cb.calls should be (0,1)
        }

        it("can be turned into several runnable, but runs once only (case 3)") {
            val cb = new InspectableCallback
            val req = requestFor(cb)
            req addAnswerFragment (ByteBuffer allocate 256)
            val runnable3 = req failed new NetlinkException(10, "foo", 0)
            val runnable1 = req.successful()
            val runnable2 = req.expired()

            runnable3.run
            cb.calls should be (0,1)
            runnable1.run
            cb.calls should be (0,1)
            runnable2.run
            cb.calls should be (0,1)
        }

        describe("Request comparator") {

            val comp = NetlinkRequest.comparator
            val a = requestFor(null)
            val b = requestFor(null)
            val c = requestFor(null)

            it("can order different requests") {
                comp.compare(a,b) should be < 0
                comp.compare(b,a) should be > 0
                comp.compare(b,c) should be < 0
                comp.compare(c,b) should be > 0
                comp.compare(a,c) should be < 0
                comp.compare(c,a) should be > 0
            }

            it("can compare a request with itself and return 0") {
                List(a,b,c) foreach { r => comp.compare(r,r) shouldBe 0 }
            }

            it("can handle one null value") {
                comp.compare(null,a) should be > 0
                comp.compare(a,null) should be < 0
            }

            it("can handle two null values") {
                comp.compare(null,null) shouldBe 0
            }
        }
    }

    describe("MultiNetlinkRequest") {

        it("gives back an empty set of object when there is no answers") {
            val cb = new SetCallback

            (NetlinkRequest makeMulti (cb, reader, null, 1000)).successful.run

            cb.set should have size(0)
        }

        it("constructs a set from the deserialized answer fragments it gets") {
            val cb = new SetCallback
            val req = NetlinkRequest makeMulti (cb, reader, null, 1000)
            val buf = ByteBuffer allocate 256

            (1 to 10) foreach { i =>
                buf.putInt(i)
                buf.flip
                req.addAnswerFragment(buf)
                buf.flip
            }

            req.successful.run

            (1 to 10) foreach { cb.set should contain(_) }
        }

        class SetCallback extends Callback[JSet[Int]] {
            var set: JSet[Int] = null
            override def onSuccess(data: JSet[Int]) { set = data }
            override def onError(e: NetlinkException) { }
        }
    }

    describe("SingleNetlinkRequest") {

        it("forces a deserialisation call with null when ACK only") {
            val cb = new IntCallback
            requestFor(cb).successful.run
            cb.x shouldBe 0
        }

        class IntCallback extends Callback[Int] {
            var x: Int = -1
            override def onSuccess(data: Int) { x = data }
            override def onError(e: NetlinkException) { }
        }
    }

    class InspectableCallback extends Callback[Int] {
        var successCalls: Int = 0
        var errorCalls: Int = 0
        def calls = (successCalls, errorCalls)
        override def onSuccess(data: Int) { successCalls += 1 }
        override def onError(e: NetlinkException) { errorCalls += 1 }
    }

    def requestFor(cb: Callback[Int], buf: ByteBuffer = null) = {
        val req = NetlinkRequest makeSingle (cb, reader, buf, 1000)
        req.seq = getSeq()
        req
    }

    val reader = new Reader[Int] {
        def deserializeFrom(buf: ByteBuffer) = Try { buf.getInt } getOrElse 0
    }

    var seq: Int = 0

    def getSeq() = { seq += 1; seq }
}
