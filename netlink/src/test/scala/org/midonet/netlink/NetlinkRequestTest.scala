/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.netlink

import java.nio.ByteBuffer
import java.util.{List => JList}
import scala.util.Try

import com.google.common.base.Function;
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.netlink.exceptions.NetlinkException;

@RunWith(classOf[JUnitRunner])
class NetlinkRequestTest extends FunSpec with Matchers {

    import AbstractNetlinkConnection.NetlinkRequest

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
                val runnable = req failed new NetlinkException(10, "foo")

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
            val runnable3 = req failed new NetlinkException(10, "foo")

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
            val runnable3 = req failed new NetlinkException(10, "foo")
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
            val runnable3 = req failed new NetlinkException(10, "foo")
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

            val comp = AbstractNetlinkConnection.requestComparator
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

    class InspectableCallback extends Callback[Int] {
        var successCalls: Int = 0
        var errorCalls: Int = 0
        def calls = (successCalls, errorCalls)
        override def onSuccess(data: Int) { successCalls += 1 }
        override def onError(e: NetlinkException) { errorCalls += 1 }
    }

    def requestFor(cb: Callback[Int], buf: ByteBuffer = null) = {
        val req = NetlinkRequest make (cb, trans, buf, getSeq, 1000)
        req
    }

    val trans = new Function[JList[ByteBuffer],Int] {
        override def apply(ls: JList[ByteBuffer]): Int =
            Try { ls.get(0).getInt } getOrElse 0
    }

    var seq: Int = 0

    def getSeq() = { seq += 1; seq }
}
