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

package org.midonet.netlink

import java.nio.ByteBuffer
import java.util.{TimerTask, Timer}

import scala.collection.mutable

import org.slf4j.Logger
import rx.Observer

import org.midonet.netlink.clib.cLibrary
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.rtnetlink.Rtnetlink

case class NetlinkHeader(len: Int, t: Short, flags: Short, seq: Int, pid: Int)

object NetlinkConnection {
    val DefaultMaxRequests = 8
    val DefaultMaxRequestSize = 512
    val DefaultRetries = 10
    val DefaultRetryIntervalMillis = 50
    val DefaultNetlinkGroup = Rtnetlink.Group.LINK.bitmask |
        Rtnetlink.Group.NOTIFY.bitmask |
        Rtnetlink.Group.NEIGH.bitmask |
        Rtnetlink.Group.TC.bitmask |
        Rtnetlink.Group.IPV4_IFADDR.bitmask |
        Rtnetlink.Group.IPV4_MROUTE.bitmask |
        Rtnetlink.Group.IPV4_ROUTE.bitmask |
        Rtnetlink.Group.IPV6_IFADDR.bitmask |
        Rtnetlink.Group.IPV6_MROUTE.bitmask |
        Rtnetlink.Group.IPV6_ROUTE.bitmask |
        Rtnetlink.Group.IPV6_PREFIX.bitmask |
        Rtnetlink.Group.IPV6_RULE.bitmask
    val InitialSeq = -1
    val NetlinkReadBufSize = 0x10000

    val AlwaysTrueReader: Reader[Boolean] = new Reader[Boolean] {
        override def deserializeFrom(source: ByteBuffer) = true
    }

    def readNetlinkHeader(reply: ByteBuffer): NetlinkHeader = {
        reply.flip()
        reply.mark()
        val finalLimit = reply.limit()

        val position: Int = reply.position

        val len: Int = reply.getInt
        val nlType: Short = reply.getShort
        val flags: Short = reply.getShort
        val seq: Int = reply.getInt
        val pid: Int = reply.getInt

        val nextPosition: Int = position + len
        reply.limit(nextPosition)

        new NetlinkHeader(len, nlType, flags, seq, pid)
    }
}

/**
 * Netlink connection interface with NetlinkRequestBroker
 */
trait NetlinkConnection {
    import NetlinkConnection._

    val pid: Int
    val notificationObserver: Observer[ByteBuffer] = null

    protected val log: Logger
    protected val requestBroker: NetlinkRequestBroker

    val retryTable = mutable.Map[Int, ByteBuffer => Unit]()

    protected def sendRequest(observer: Observer[ByteBuffer])
                             (prepare: ByteBuffer => Unit): Int = {
        val seq: Int = requestBroker.nextSequence()
        val buf: ByteBuffer = requestBroker.get(seq)
        prepare(buf)
        requestBroker.publishRequest(seq, observer)
        requestBroker.writePublishedRequests()
        seq
    }

    protected def sendRetryRequest(observer: Observer[ByteBuffer],
                                   retryObserver: RetryObserver[_])
                                  (prepare: ByteBuffer => Unit): Int = {
        val seq: Int = requestBroker.nextSequence()
        val buf: ByteBuffer = requestBroker.get(seq)
        if (retryObserver.seq == InitialSeq) {
            retryObserver.seq = seq
            retryTable += (seq -> prepare)
        }
        prepare(buf)
        requestBroker.publishRequest(seq, observer)
        requestBroker.writePublishedRequests()
        seq
    }

    protected def sendRetryRequest(observer: Observer[ByteBuffer],
                                   retryObserver: RetrySetObserver[_])
                                  (prepare: ByteBuffer => Unit): Int = {
        val seq: Int = requestBroker.nextSequence()
        val buf: ByteBuffer = requestBroker.get(seq)
        if (retryObserver.seq == InitialSeq) {
            retryObserver.seq = seq
            retryTable += (seq -> prepare)
        }
        prepare(buf)
        requestBroker.publishRequest(seq, observer)
        requestBroker.writePublishedRequests()
        seq
    }

    private def processFailedRequest(seq: Int, error: Int,
                                     observer: Observer[ByteBuffer]): Unit = {
        val errorMessage: String = cLibrary.lib.strerror(-error)
        val err: NetlinkException = new NetlinkException(-error, errorMessage)
        observer.onError(err)
        log.error(cLibrary.lib.strerror(-error))
    }

    protected
    class RetryObserver[T](observer: Observer[T],
                           var seq: Int, retryCount: Int)
                          (implicit reader: Reader[T]) extends Observer[T] {
        override def onCompleted(): Unit = {
            log.debug("Retry for seq {} was succeeded at retry {}",
                seq, retryCount)
            retryTable -= seq
            observer.onCompleted()
        }

        override def onError(t: Throwable): Unit = t match {
            case e: NetlinkException
                if e.getErrorCodeEnum == NetlinkException.ErrorCode.EBUSY =>
                if (retryCount > 0 && retryTable.contains(seq)) {
                    log.debug(s"Scheduling new RetryObserver($retryCount) " +
                        "for seq {}, {}", seq, reader)
                    val timer = new Timer(this.getClass.getName + "-resend")
                    timer.schedule(new TimerTask() {
                        def run(): Unit = {
                            val retryObserver = new RetryObserver[T](observer,
                                seq, retryCount - 1)
                            val obs: Observer[ByteBuffer] =
                                bb2Resource(reader)(retryObserver)
                            sendRetryRequest(obs, retryObserver)(
                                retryTable(seq))
                            log.debug("Resent a request for seq {} at retry {}",
                                seq, retryCount)
                        }
                    }, DefaultRetryIntervalMillis)
                }
            case e: Exception =>
                log.debug("Other errors happened for seq {}: {}", seq, e)
                observer.onError(t)
        }

        override def onNext(r: T): Unit = {
            log.debug(s"Retry for seq $seq got {} at retry {}", r, retryCount)
            observer.onNext(r)
        }
    }

    protected
    class RetrySetObserver[T](observer: Observer[Set[T]],
                              var seq: Int, retryCount: Int)
                             (implicit reader: Reader[T])
            extends Observer[Set[T]] {
        override def onCompleted(): Unit = {
            log.debug("Retry for seq {} was succeeded at retry {}",
                seq, retryCount)
            retryTable -= seq
            observer.onCompleted()
        }

        override def onError(t: Throwable): Unit = t match {
            case e: NetlinkException
                if e.getErrorCodeEnum == NetlinkException.ErrorCode.EBUSY =>
                if (retryCount > 0 && retryTable.contains(seq)) {
                    log.debug(s"Scheduling new RetryObserver($retryCount) " +
                        "for seq {}, {}", seq, reader)
                    val timer = new Timer(this.getClass.getName + "-resend")
                    timer.schedule(new TimerTask() {
                        override def run(): Unit = {
                            val retryObserver = new RetrySetObserver[T](
                                observer, seq, retryCount - 1)
                            val obs: Observer[ByteBuffer] =
                                bb2ResourceSet(reader)(retryObserver)
                            sendRetryRequest(obs, retryObserver)(
                                retryTable(seq))
                            log.debug("Resent a request for seq {} at retry {}",
                                seq, retryCount)
                        }
                    }, DefaultRetryIntervalMillis)
                }
            case e: Exception =>
                log.debug("Other errors happened for seq {}: {}", seq, e)
                observer.onError(t)
        }

        override def onNext(r: Set[T]): Unit = {
            log.debug(s"Retry for seq $seq got {} at retry {}", r, retryCount)
            observer.onNext(r)
        }
    }

    protected
    def toRetriable[T](observer: Observer[T], seq: Int = InitialSeq,
                       retryCounter: Int = DefaultRetries)
                      (implicit reader: Reader[T]): RetryObserver[T] =
        new RetryObserver[T](observer, seq, retryCounter) // (reader)

    protected
    def toRetriableSet[T](observer: Observer[Set[T]], seq: Int = InitialSeq,
                          retryCount: Int = DefaultRetries)
                         (implicit reader: Reader[T]): RetrySetObserver[T] =
        new RetrySetObserver[T](observer, seq, retryCount) // (reader)

    protected
    def bb2Resource[T](reader: Reader[T])
                      (observer: Observer[T]): Observer[ByteBuffer] =
        new Observer[ByteBuffer] {
            override def onCompleted(): Unit = observer.onCompleted()
            override def onError(e: Throwable): Unit = observer.onError(e)
            override def onNext(buf: ByteBuffer): Unit = {
                val resource: T = reader.deserializeFrom(buf)
                observer.onNext(resource)
            }
        }

    protected
    def bb2ResourceSet[T](reader: Reader[T])
                         (observer: Observer[Set[T]]): Observer[ByteBuffer] =
        new Observer[ByteBuffer] {
            private val resources =  mutable.Set[T]()
            override def onCompleted(): Unit = {
                observer.onNext(resources.toSet)
                observer.onCompleted()
            }
            override def onError(e: Throwable): Unit = observer.onError(e)
            override def onNext(buf: ByteBuffer): Unit = {
                val resource: T = reader.deserializeFrom(buf)
                resources += resource
            }
        }
}
