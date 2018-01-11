/*
 * Copyright 2018 Midokura SARL
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

import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.invocation.InvocationOnMock
import org.mockito.Matchers.any
import org.mockito.Matchers.argThat
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.when
import org.mockito.MockitoAnnotations
import org.mockito.stubbing.Answer
import org.scalatest.BeforeAndAfter
import org.scalatest.FeatureSpecLike
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar

import java.nio.ByteBuffer

import scala.util.Try

import org.midonet.ErrorCode
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.util.BatchCollector
import org.midonet.util.Bucket
import org.midonet.util.MidonetEventually


@RunWith(classOf[JUnitRunner])
class AbstractNetlinkConnectionTest extends FeatureSpecLike
        with Matchers
        with BeforeAndAfter
        with MockitoSugar
        with MidonetEventually {

    @Mock
    var mockNetlinkChannel: NetlinkChannel = _
    @Mock
    var mockBufferPool: BufferPool = _
    @Mock
    var mockBucket: Bucket = _

    before {
        MockitoAnnotations.initMocks(this);
    }

    feature("AbstractNetlinkConnection") {
        scenario("success") {
            val conn = getConnection
            val dispatcher = getDispatcher
            conn.setCallbackDispatcher(dispatcher)

            val callback = mock[Callback[Int]]
            val buf = ByteBuffer.allocate(128)
            val timeout = 1 * 1000  // 1 second
            conn.sendTestMessage(buf, callback, reader, timeout)
            eventually {
                conn.handleWriteEvent()
                verify(mockNetlinkChannel).write(buf)
            }
            val seq = readSequenceNumber(buf)
            seq should not be (0)

            val payload = 4321
            when(mockNetlinkChannel.read(any(classOf[ByteBuffer])))
                .thenAnswer(new Answer[Int]() {
                    def answer(invocation: InvocationOnMock): Int = {
                        val args = invocation.getArguments()
                        val reply = args(0).asInstanceOf[ByteBuffer]

                        // Fake up a reply
                        reply.position(NetlinkMessage.GENL_HEADER_SIZE)
                        reply.putInt(payload)
                        val size = reply.position()
                        reply.position(0)
                        NetlinkMessage.writeHeader(reply,
                            size,
                            (NLMessageType.NLMSG_MIN_TYPE + 1),
                            0,  // flags
                            seq,
                            0,  // pid
                            0,  // command
                            0)  // version
                        reply.position(size)
                        size
                    }
                })
                .thenReturn(0)  // EOF
            conn.handleReadEvent(mockBucket)
            eventually {
                verify(callback).onSuccess(payload)
            }
            dispatcher.currentCBs should be (Nil)
            dispatcher.doneCBs.flatten.size should be (1)
        }

        scenario("timeout") {
            val conn = getConnection
            val dispatcher = getDispatcher
            conn.setCallbackDispatcher(dispatcher)

            val callback = mock[Callback[Int]]
            val buf = ByteBuffer.allocate(128)
            val timeout = 1 * 1000  // 1 second
            conn.sendTestMessage(buf, callback, reader, timeout)
            eventually {
                conn.handleWriteEvent()
                verify(callback).onError(
                    argThat(new ArgumentMatcher[NetlinkException]() {
                        override def matches(arg: Any) = {
                            val e = arg.asInstanceOf[NetlinkException]

                            e.getErrorCodeEnum == ErrorCode.ETIMEOUT
                        }
                    }))
            }
            val seq = readSequenceNumber(buf)
            seq should not be (0)
            dispatcher.currentCBs should be (Nil)
            dispatcher.doneCBs.flatten.size should be (1)
        }
    }

    val reader = new Reader[Int] {
        def deserializeFrom(buf: ByteBuffer) = Try { buf.getInt } getOrElse 0
    }

    def getConnection = new TestableNetlinkConnection(
        mockNetlinkChannel,
        mockBufferPool,
        new NullNetlinkMetrics
    )

    def getDispatcher = new BatchCollector[Runnable] {
        var doneCBs: List[List[Runnable]] = Nil
        var currentCBs: List[Runnable] = Nil

        override def endBatch() {
            this.synchronized {
                currentCBs.foreach(_.run)
                doneCBs ::= currentCBs
                currentCBs = Nil
            }
        }

        override def submit(r: Runnable): Boolean = {
            this.synchronized {
                currentCBs ::= r
            }
            true
        }
    }

    def readSequenceNumber(buf: ByteBuffer) = {
        buf.getInt(buf.position() + NetlinkMessage.NLMSG_SEQ_OFFSET)
    }
}

class TestableNetlinkConnection(
        channel: NetlinkChannel,
        sendPool: BufferPool,
        metrics: NetlinkMetrics
    ) extends AbstractNetlinkConnection(
        channel,
        sendPool,
        metrics
    ) {

    override protected def pid = 123  // just to avoid NPE

    override protected def handleNotification(
        typ: Short, cmd: Byte, seq: Int, pid: Int,
        buffer: ByteBuffer) = false

    def sendTestMessage(payload: ByteBuffer,
                        callback: Callback[Int],
                        reader: Reader[Int],
                        timeoutMillis: Long) = {
        sendNetlinkMessage(payload, callback, reader, timeoutMillis)
    }
}
