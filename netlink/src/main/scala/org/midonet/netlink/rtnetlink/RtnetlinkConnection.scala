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

package org.midonet.netlink.rtnetlink

import java.nio.ByteBuffer

import scala.collection.mutable

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import rx.Observer

import org.midonet.netlink._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.NanoClock

/**
 * Abstracted interfaces for rtnetlink operations exposed publicly.
 */
trait AbstractRtnetlinkConnection {
    def linksList(observer: Observer[Set[Link]]): Unit
    def linksGet(ifindex: Int, observer: Observer[Link]): Unit
    def linksCreate(link: Link, observer: Observer[Link]): Unit
    def linksSetAddr(link: Link, mac: MAC,
                     observer: Observer[Boolean]): Unit
    def linksSet(link: Link, observer: Observer[Boolean]): Unit

    def addrsList(observer: Observer[Set[Addr]]): Unit
    def addrsGet(ifIndex: Int, observer: Observer[Set[Addr]]): Unit
    def addrsGet(ifIndex: Int, family: Byte,
                 observer: Observer[Set[Addr]]): Unit

    def routesList(observer: Observer[Set[Route]]): Unit
    def routesGet(dst: IPv4Addr, observer: Observer[Route]): Unit
    def routesCreate(dst: IPv4Addr, prefix: Int, gw: IPv4Addr, link: Link,
                     observer: Observer[Boolean]): Unit

    def neighsList(observer: Observer[Set[Neigh]]): Unit
}

/**
 * Default implementation of rtnetlink connection.
 *
 * RtnetlinkConnection provides interfaces to make rtnetlink requests. Users
 * are responsible for reading corresponding replies. NetlinkRequestBroker takes
 * care of writing requests and reading replies underlyig and You MUST avoid
 * reading replies with NetlinkRequestBroker::readReply in onNext methods of
 * the observers that are passed to the request interfaces since their onNext
 * methods are called before clearing the read buffer shared among the replies.
 *
 * @param channel the channel to be used for reading/writing requests/replies.
 * @param maxPendingRequests the maximum number of pending requests.
 * @param maxRequestSize the maximum number of requests size.
 * @param clock the clock passed to the broker.
 */
class RtnetlinkConnection(val channel: NetlinkChannel,
                          maxPendingRequests: Int,
                          maxRequestSize: Int,
                          clock: NanoClock)
         extends AbstractRtnetlinkConnection {
    import NetlinkUtil._

    val pid: Int = channel.getLocalAddress.getPid
    protected val log = Logger(LoggerFactory.getLogger(
        "org.midonet.netlink.rtnetlink-conn-" + pid))
    private val protocol = new RtnetlinkProtocol(pid)

    protected val reader = new NetlinkReader(channel)
    protected val writer = new NetlinkBlockingWriter(channel)
    protected val readBuf =
        BytesUtil.instance.allocateDirect(NETLINK_READ_BUF_SIZE)
    val requestBroker = new NetlinkRequestBroker(writer, reader,
        maxPendingRequests, maxRequestSize, readBuf, clock)

    protected def sendRequest(observer: Observer[ByteBuffer])
                             (prepare: ByteBuffer => Unit): Long = {
        val seq: Long = requestBroker.nextSequence()
        val buf: ByteBuffer = requestBroker.get(seq)
        prepare(buf)
        requestBroker.publishRequest(seq, observer)
        requestBroker.writePublishedRequests()
        seq
    }

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
            private val resources = mutable.Set[T]()

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

    /**
     * ResourceObserver creates an observer and call the closure given by the
     * users when onNext is invoked.
     */
    protected object ResourceObserver {
        def apply[T](closure: (T) => Any): ResourceObserver[T] =
            new ResourceObserver[T] {
                override def onNext(r: T): Unit = closure(r)
            }
    }

    /**
     * ResourceObserver ignores onCompleted and onError calls and just emits the
     * logs.
     *
     * @tparam T the type of the rtnetlink resources.
     */
    protected abstract class ResourceObserver[T] extends Observer[T] {
        override def onCompleted(): Unit =
            log.debug("ResourceObserver is completed.")

        override def onError(e: Throwable): Unit =
            log.error(s"ResourceObserver got an error: $e")
    }

    /**
     * Convert the given closure to an observer. Because this needs the type
     * hint to determine the type parameter, users are required to explicitly
     * annotate the type of the argument of the closure withe parentheses.
     *
     *   e.g., (link: Link) => ...
     *
     * @param closure the closure to be used as onNext of the observer.
     * @tparam T the type of the argument of the closure.
     * @return Resourceobserver which onNext is the given closure.
     */
    implicit
    def closureToObserver[T](closure: (T) => Any): Observer[T] =
        ResourceObserver.apply[T](closure)

    implicit
    def linkObserver(observer: Observer[Link]): Observer[ByteBuffer] =
        bb2Resource(Link.deserializer)(observer)

    implicit
    def addrObserver(observer: Observer[Addr]): Observer[ByteBuffer] =
        bb2Resource(Addr.deserializer)(observer)

    implicit
    def routeObserver(observer: Observer[Route]): Observer[ByteBuffer] =
        bb2Resource(Route.deserializer)(observer)

    implicit
    def neighObserver(observer: Observer[Neigh]): Observer[ByteBuffer] =
        bb2Resource(Neigh.deserializer)(observer)

    implicit
    def linkSetObserver(observer: Observer[Set[Link]]): Observer[ByteBuffer] =
        bb2ResourceSet(Link.deserializer)(observer)

    implicit
    def addrSetObserver(observer: Observer[Set[Addr]]): Observer[ByteBuffer] =
        bb2ResourceSet(Addr.deserializer)(observer)

    implicit
    def routeSetObserver(observer: Observer[Set[Route]]): Observer[ByteBuffer] =
        bb2ResourceSet(Route.deserializer)(observer)

    implicit
    def neighSetObserver(observer: Observer[Set[Neigh]]): Observer[ByteBuffer] =
        bb2ResourceSet(Neigh.deserializer)(observer)

    implicit
    def booleanObserver(observer: Observer[Boolean]): Observer[ByteBuffer] =
        bb2Resource(AlwaysTrueReader)(observer)

    override def linksList(observer: Observer[Set[Link]]): Unit =
        sendRequest(observer)(protocol.prepareLinkList)

    override def linksGet(ifindex: Int, observer: Observer[Link]): Unit =
        sendRequest(observer)(buf => protocol.prepareLinkGet(buf, ifindex))

    override def linksCreate(link: Link, observer: Observer[Link]): Unit =
        sendRequest(observer)(buf => protocol.prepareLinkCreate(buf, link))

    override def linksSetAddr(link: Link, mac: MAC,
                              observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf =>
            protocol.prepareLinkSetAddr(buf, link, mac))

    override def linksSet(link: Link, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => protocol.prepareLinkSet(buf, link))

    override def addrsList(observer: Observer[Set[Addr]]): Unit =
        sendRequest(observer)(protocol.prepareAddrList)

    override def addrsGet(ifIndex: Int, observer: Observer[Set[Addr]]): Unit =
        sendRequest(observer)(buf =>
            protocol.prepareAddrGet(buf, ifIndex, Addr.Family.AF_INET))

    override def addrsGet(ifIndex: Int, family: Byte,
                          observer: Observer[Set[Addr]]): Unit =
        sendRequest(observer)(buf =>
            protocol.prepareAddrGet(buf, ifIndex, family))

    override def addrsSet(addr: Addr, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => protocol.prepareAddrSet(buf, addr))

    override def routesList(observer: Observer[Set[Route]]): Unit =
        sendRequest(observer)(protocol.prepareRouteList)

    override def routesGet(dst: IPv4Addr, observer: Observer[Route]): Unit =
        sendRequest(observer)(buf => protocol.prepareRouteGet(buf, dst))

    override def routesCreate(dst: IPv4Addr, prefix: Int, gw: IPv4Addr,
                              link: Link, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf =>
            protocol.prepareRouteNew(buf, dst, prefix, gw, link))

    override def neighsList(observer: Observer[Set[Neigh]]): Unit =
        sendRequest(observer)(protocol.prepareNeighList)
}

