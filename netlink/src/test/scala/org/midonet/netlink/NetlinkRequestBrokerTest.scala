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
                               with ShouldMatchers
                               with OneInstancePerTest {

    val maxRequests = 8
    val channel = new MockNetlinkChannel(Netlink.selectorProvider,
                                         NetlinkProtocol.NETLINK_GENERIC) {
        override def read(dst: ByteBuffer) = dst.remaining()
    }

    {
        channel.connect(new Address(0))
    }

    val reader = new NetlinkReader(channel)
    val writer = new MockNetlinkWriter
    val clock = new MockClock
    val requestBuf = ByteBuffer.allocate(512)
    val replyBuf = ByteBuffer.allocate(1024)
    val requestReply = new NetlinkRequestBroker(reader, writer, maxRequests, replyBuf,
                                               clock, timeout = 1 milli)

    class CountingObserver extends Observer[ByteBuffer] {
        var onNextCalls = 0
        var onErrorCalls = 0
        var onCompleteCalls = 0

        override def onCompleted(): Unit = onCompleteCalls += 1
        override def onError(e: Throwable): Unit = onErrorCalls += 1
        override def onNext(t: ByteBuffer): Unit = onNextCalls += 1
    }

    feature ("Can make requests to a NetlinkRequestBroker") {
        scenario ("A sequence number is added to a netlink request") {
            NetlinkMessage.writeHeader(requestBuf, 256, 1, 2, 3 /* seq */, 4, 5, 6)

            requestReply.writeRequest(requestBuf, null) should be (512)
            requestBuf.getInt(NetlinkMessage.NLMSG_SEQ_OFFSET) should be (1)
        }

        scenario ("Errors are communicated through the Observer") {
            writer.shouldThrow = true
            val obs = new CountingObserver
            requestReply.writeRequest(requestBuf, obs) should be (0)
            obs.onErrorCalls should be (1)
        }

        scenario ("Number of in-flight requests is bounded") {
            (1 to maxRequests) foreach { i =>
                requestReply.writeRequest(requestBuf, null) should be (512)
                requestBuf.getInt(NetlinkMessage.NLMSG_SEQ_OFFSET) should be (i)
            }
            requestReply.writeRequest(requestBuf, null) should be (0)
        }
    }

    feature ("Can get replies from a NetlinkRequestBroker") {
        scenario ("An ACK calls into onComplete") {
            val obs = new CountingObserver

            requestReply.writeRequest(requestBuf, obs)
            val seq = requestBuf.getInt(NetlinkMessage.NLMSG_SEQ_OFFSET)

            val size = NetlinkMessage.NLMSG_ERROR_SIZE + NetlinkMessage.NLMSG_ERROR_HEADER_SIZE
            NetlinkMessage.writeHeader(replyBuf, size, NLMessageType.ERROR, 0, seq, 0, 0, 0)

            replyBuf.putInt(NetlinkMessage.NLMSG_ERROR_OFFSET, 0)
            replyBuf.limit(size)
            requestReply.readReply()
            obs.onCompleteCalls should be (1)
            obs.onNextCalls should be (0)
        }

        scenario ("A no-op calls into onComplete") {
            val obs = new CountingObserver

            requestReply.writeRequest(requestBuf, obs)
            val seq = requestBuf.getInt(NetlinkMessage.NLMSG_SEQ_OFFSET)

            val size = NetlinkMessage.HEADER_SIZE
            NetlinkMessage.writeHeader(replyBuf, size , 0, 0, seq, 0, 0, 0)

            replyBuf.limit(size)
            requestReply.readReply()
            obs.onCompleteCalls should be (1)
            obs.onNextCalls should be (0)
        }

        scenario ("An error calls into onError") {
            val obs = new CountingObserver

            requestReply.writeRequest(requestBuf, obs)
            val seq = requestBuf.getInt(NetlinkMessage.NLMSG_SEQ_OFFSET)

            val size = NetlinkMessage.NLMSG_ERROR_SIZE + NetlinkMessage.NLMSG_ERROR_HEADER_SIZE
            NetlinkMessage.writeHeader(replyBuf, size, NLMessageType.ERROR, 0, seq, 0, 0, 0)

            replyBuf.putInt(NetlinkMessage.NLMSG_ERROR_OFFSET, -24)
            replyBuf.limit(size)
            requestReply.readReply()
            obs.onErrorCalls should be (1)
            obs.onNextCalls should be (0)
            obs.onCompleteCalls should be (0)
        }

        scenario ("An answer calls into onNext and onComplete") {
            val obs = new CountingObserver {
                override def onNext(buf: ByteBuffer) = {
                    buf.remaining() should be (16)
                    super.onNext(buf)
                }
            }

            requestReply.writeRequest(requestBuf, obs)
            val seq = requestBuf.getInt(NetlinkMessage.NLMSG_SEQ_OFFSET)

            val size = NetlinkMessage.GENL_HEADER_SIZE + 16
            NetlinkMessage.writeHeader(replyBuf, size, 160, 0, seq, 0, 0, 0)
            replyBuf.limit(size)

            requestReply.readReply()
            obs.onNextCalls should be (1)
            obs.onCompleteCalls should be (1)
        }

        scenario ("Can have multiple answers per buffer") {
            val obs = new CountingObserver {
                override def onNext(buf: ByteBuffer) = {
                    buf.remaining() should be (16)
                    super.onNext(buf)
                }
            }

            requestReply.writeRequest(requestBuf, obs)
            val seq1 = requestBuf.getInt(NetlinkMessage.NLMSG_SEQ_OFFSET)
            requestReply.writeRequest(requestBuf, obs)
            val seq2 = requestBuf.getInt(NetlinkMessage.NLMSG_SEQ_OFFSET)

            val size = NetlinkMessage.GENL_HEADER_SIZE + 16
            NetlinkMessage.writeHeader(replyBuf, size, 16, 0, seq1, 0, 0, 0)
            replyBuf.position(size)
            NetlinkMessage.writeHeader(replyBuf, size, 16, 0, seq2, 0, 0, 0)
            replyBuf.position(0)
            replyBuf.limit(size*2)

            requestReply.readReply()
            obs.onNextCalls should be (2)
            obs.onCompleteCalls should be (2)
        }

        scenario ("A multipart answer calls multiple times into onNext") {
            val obs = new CountingObserver {
                override def onNext(buf: ByteBuffer) = {
                    buf.remaining() should be (16)
                    super.onNext(buf)
                }
            }

            requestReply.writeRequest(requestBuf, obs)
            val seq = requestBuf.getInt(NetlinkMessage.NLMSG_SEQ_OFFSET)

            val size = NetlinkMessage.GENL_HEADER_SIZE + 16
            NetlinkMessage.writeHeader(replyBuf, size, 16, 0, seq, 0, 0, 0)
            replyBuf.putShort(NetlinkMessage.NLMSG_FLAGS_OFFSET, NLFlag.MULTI)
            replyBuf.limit(size)

            requestReply.readReply()
            replyBuf.limit(size)
            requestReply.readReply()

            NetlinkMessage.writeHeader(replyBuf, NetlinkMessage.GENL_HEADER_SIZE,
                                       NLMessageType.DONE, 0, seq, 0, 0, 0)
            replyBuf.limit(NetlinkMessage.GENL_HEADER_SIZE)
            requestReply.readReply()

            obs.onNextCalls should be (2)
            obs.onCompleteCalls should be (1)
        }

        scenario ("An upcall packet is passed into a catch-all Observer") {
            val obs = new CountingObserver {
                override def onNext(buf: ByteBuffer) = {
                    buf.remaining() should be (16)
                    super.onNext(buf)
                }
            }
            val size = NetlinkMessage.GENL_HEADER_SIZE + 16
            NetlinkMessage.writeHeader(replyBuf, size, 16, 0, 0, 0, 0, 0)
            replyBuf.limit(size)

            requestReply.readReply(obs)

            obs.onNextCalls should be (1)
            obs.onCompleteCalls should be (1)
        }

        scenario ("A truncated packet is detected") {
            val obs = new CountingObserver {
                override def onError(t: Throwable) = {
                    t should be (NetlinkReader.MessageTruncated)
                    super.onError(t)
                }
            }

            requestReply.writeRequest(requestBuf, obs)
            val seq = requestBuf.getInt(NetlinkMessage.NLMSG_SEQ_OFFSET)

            NetlinkMessage.writeHeader(replyBuf, 1024, 0, 0, seq, 0, 0, 0)

            replyBuf.limit(512)
            requestReply.readReply()
            obs.onErrorCalls should be (1)
        }

    }

    feature ("Requests expire") {
        scenario ("Expirations are processed after a reply") {
            val obs = new CountingObserver

            clock.time = 0
            requestReply.writeRequest(requestBuf, obs)

            clock.time = (1 milli).toNanos
            requestReply.writeRequest(requestBuf, obs)
            requestReply.writeRequest(requestBuf, obs)

            clock.time = (1 milli).toNanos + 1
            replyBuf.limit(0)
            requestReply.readReply()
            obs.onErrorCalls should be (1)

            clock.time *= 2
            replyBuf.limit(0)
            requestReply.readReply()
            obs.onErrorCalls should be (3)
        }
    }
}
