/*
 * Copyright 2017 Midokura SARL
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

import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import rx.Observer

import org.midonet.util.concurrent.MockClock
import org.midonet.netlink.Netlink.Address

@RunWith(classOf[JUnitRunner])
class NetlinkRequestBrokerTest extends FeatureSpec
                               with BeforeAndAfter
                               with Matchers
                               with OneInstancePerTest {

    class CountingObserver extends Observer[ByteBuffer] {
        var onNextCalls = 0
        var onErrorCalls = 0
        var onCompletedCalls = 0

        override def onCompleted(): Unit = onCompletedCalls += 1
        override def onError(e: Throwable): Unit = onErrorCalls += 1
        override def onNext(t: ByteBuffer): Unit = onNextCalls += 1
    }

    val maxRequests = 8
    val replyBuf = ByteBuffer.allocate(1024)

    val channel = new MockNetlinkChannel(Netlink.selectorProvider,
                                         NetlinkProtocol.NETLINK_GENERIC) {
        override def read(dst: ByteBuffer) = dst.put(replyBuf).remaining()
    }

    {
        channel.connect(new Address(0))
    }

    val reader = new NetlinkReader(channel)
    val writer = new MockNetlinkWriter
    val clock = new MockClock
    val broker = new NetlinkRequestBroker(writer, reader, maxRequests, 512,
                                          ByteBuffer.allocate(1024), clock,
                                          timeout = 1 milli)

    before {
        replyBuf.clear()
    }

    feature ("Can make requests to a NetlinkRequestBroker") {
        scenario ("A sequence number is added to a netlink request") {
            val seq = broker.nextSequence()
            seq should be (0)

            NetlinkMessage.writeHeader(broker.get(seq), 256, 1, 2, 3 /* seq */, 4, 5, 6)
            broker.publishRequest(seq, null)
            broker.writePublishedRequests()
            broker.get(seq).getInt(NetlinkMessage.NLMSG_SEQ_OFFSET) should be (0)
        }

        scenario ("Errors are communicated through the Observer") {
            writer.shouldThrow = true
            val obs = new CountingObserver
            broker.publishRequest(broker.nextSequence(), obs)
            broker.writePublishedRequests() should be (0)
            obs.onErrorCalls should be (1)
        }

        scenario ("Number of in-flight requests is bounded") {
            (0 until maxRequests) foreach { i =>
                broker.nextSequence() should be (i)
                broker.publishRequest(i, null)
            }
            broker.nextSequence() should be (NetlinkRequestBroker.FULL)
        }
    }

    feature ("Can get replies from a NetlinkRequestBroker") {
        scenario ("An ACK calls into onComplete") {
            val obs = new CountingObserver

            val seq = broker.nextSequence()
            broker.publishRequest(seq, obs)
            broker.writePublishedRequests()

            val size = NetlinkMessage.NLMSG_ERROR_SIZE + NetlinkMessage.NLMSG_ERROR_HEADER_SIZE
            NetlinkMessage.writeHeader(replyBuf, size, NLMessageType.ERROR, 0, seq.toInt, 0, 0, 0)

            replyBuf.putInt(NetlinkMessage.NLMSG_ERROR_OFFSET, 0)
            replyBuf.limit(size)
            broker.readReply()
            obs.onCompletedCalls should be (1)
            obs.onNextCalls should be (0)
        }

        scenario ("A no-op calls into onComplete") {
            val obs = new CountingObserver

            val seq = broker.nextSequence()
            broker.publishRequest(seq, obs)
            broker.writePublishedRequests()

            val size = NetlinkMessage.HEADER_SIZE
            NetlinkMessage.writeHeader(replyBuf, size , 0, 0, seq.toInt, 0, 0, 0)

            replyBuf.limit(size)
            broker.readReply()
            obs.onCompletedCalls should be (1)
            obs.onNextCalls should be (0)
        }

        scenario ("An error calls into onError") {
            val obs = new CountingObserver

            val seq = broker.nextSequence()
            broker.publishRequest(seq, obs)
            broker.writePublishedRequests()

            val size = NetlinkMessage.NLMSG_ERROR_SIZE + NetlinkMessage.NLMSG_ERROR_HEADER_SIZE
            NetlinkMessage.writeHeader(replyBuf, size, NLMessageType.ERROR, 0, seq.toInt, 0, 0, 0)

            replyBuf.putInt(NetlinkMessage.NLMSG_ERROR_OFFSET, -24)
            replyBuf.limit(size)
            broker.readReply()
            obs.onErrorCalls should be (1)
            obs.onNextCalls should be (0)
            obs.onCompletedCalls should be (0)
        }

        scenario ("An answer calls into onNext and onComplete") {
            val obs = new CountingObserver {
                override def onNext(buf: ByteBuffer) = {
                    buf.remaining() should be (16)
                    super.onNext(buf)
                }
            }

            val seq = broker.nextSequence()
            broker.publishRequest(seq, obs)
            broker.writePublishedRequests()

            val size = NetlinkMessage.GENL_HEADER_SIZE + 16
            NetlinkMessage.writeHeader(replyBuf, size, 160, 0, seq.toInt, 0, 0, 0)
            replyBuf.limit(size)

            broker.readReply()
            obs.onNextCalls should be (1)
            obs.onCompletedCalls should be (1)
        }

        scenario ("Can have multiple answers per buffer") {
            val obs = new CountingObserver {
                override def onNext(buf: ByteBuffer) = {
                    buf.remaining() should be (16)
                    super.onNext(buf)
                }
            }

            val seq1 = broker.nextSequence()
            broker.publishRequest(seq1, obs)
            val seq2 = broker.nextSequence()
            broker.publishRequest(seq2, obs)
            broker.writePublishedRequests()

            val size = NetlinkMessage.GENL_HEADER_SIZE + 16
            NetlinkMessage.writeHeader(replyBuf, size, 16, 0, seq1.toInt, 0, 0, 0)
            replyBuf.position(size)
            NetlinkMessage.writeHeader(replyBuf, size, 16, 0, seq2.toInt, 0, 0, 0)
            replyBuf.position(0)
            replyBuf.limit(size*2)

            broker.readReply()
            obs.onNextCalls should be (2)
            obs.onCompletedCalls should be (2)
        }

        scenario ("A multipart answer calls multiple times into onNext") {
            val obs = new CountingObserver {
                override def onNext(buf: ByteBuffer) = {
                    buf.remaining() should be (16)
                    super.onNext(buf)
                }
            }

            val seq = broker.nextSequence()
            broker.publishRequest(seq, obs)
            broker.writePublishedRequests()

            val size = NetlinkMessage.GENL_HEADER_SIZE + 16
            NetlinkMessage.writeHeader(replyBuf, size, 16, 0, seq.toInt, 0, 0, 0)
            replyBuf.putShort(NetlinkMessage.NLMSG_FLAGS_OFFSET, NLFlag.MULTI)
            replyBuf.limit(size)

            broker.readReply()
            replyBuf.position(0)
            broker.readReply()
            replyBuf.position(0)

            NetlinkMessage.writeHeader(replyBuf, NetlinkMessage.GENL_HEADER_SIZE,
                                       NLMessageType.DONE, 0, seq.toInt, 0, 0, 0)
            replyBuf.position(0)
            replyBuf.limit(NetlinkMessage.GENL_HEADER_SIZE)
            broker.readReply()

            obs.onNextCalls should be (2)
            obs.onCompletedCalls should be (1)
        }

        scenario ("A reply without a registered observer is passed into a catch-all Observer") {
            val obs = new CountingObserver {
                override def onNext(buf: ByteBuffer) = {
                    buf.remaining() should be (16)
                    super.onNext(buf)
                }
            }
            val size = NetlinkMessage.GENL_HEADER_SIZE + 16
            NetlinkMessage.writeHeader(replyBuf, size, 16, 0, 1, 0, 0, 0)
            replyBuf.limit(size)

            broker.readReply(obs)

            obs.onNextCalls should be (1)
            obs.onCompletedCalls should be (1)
        }

        scenario ("A truncated packet is detected") {
            val obs = new CountingObserver {
                override def onError(t: Throwable) = {
                    t should be (NetlinkReader.MessageTruncated)
                    super.onError(t)
                }
            }

            val seq = broker.nextSequence()
            broker.publishRequest(seq, obs)
            broker.writePublishedRequests()

            NetlinkMessage.writeHeader(replyBuf, 1024, 0, 0, seq.toInt, 0, 0, 0)

            replyBuf.limit(512)
            broker.readReply()
            obs.onErrorCalls should be (1)
        }

    }

    feature("Test single element broker") {
        val broker = new NetlinkRequestBroker(
                writer, reader, 1, 512, ByteBuffer.allocate(1024), clock)

        scenario("A request is correctly carried out") {
            val obs = new CountingObserver {
                override def onNext(buf: ByteBuffer) = {
                    buf.remaining() should be (16)
                    super.onNext(buf)
                }
            }

            val seq = broker.nextSequence()
            broker.publishRequest(seq, obs)
            broker.writePublishedRequests()

            val size = NetlinkMessage.GENL_HEADER_SIZE + 16
            NetlinkMessage.writeHeader(replyBuf, size, 160, 0, seq.toInt, 0, 0, 0)
            replyBuf.limit(size)

            broker.readReply()
            obs.onNextCalls should be (1)
            obs.onCompletedCalls should be (1)
        }

        scenario("Second request returns FULL") {
            broker.nextSequence() should be (0)
            broker.nextSequence() should be (NetlinkRequestBroker.FULL)
        }
    }

    feature ("Requests expire") {
        scenario ("Expirations are processed after a reply") {
            val obs = new CountingObserver

            clock.time = 0
            broker.publishRequest(broker.nextSequence(), obs)
            broker.writePublishedRequests()

            clock.time = (1 milli).toNanos
            broker.publishRequest(broker.nextSequence(), obs)
            broker.publishRequest(broker.nextSequence(), obs)
            broker.writePublishedRequests()

            clock.time = (1 milli).toNanos + 1
            replyBuf.limit(0)
            broker.readReply()
            obs.onErrorCalls should be (1)

            clock.time *= 2
            replyBuf.limit(0)
            broker.readReply()
            obs.onErrorCalls should be (3)
        }
    }
}
