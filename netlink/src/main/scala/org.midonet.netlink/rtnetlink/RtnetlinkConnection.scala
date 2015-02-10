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

import scala.reflect.ClassTag
import scala.reflect.runtime.{universe => ru}

import org.slf4j.{Logger, LoggerFactory}
import rx.Observer

import org.midonet.netlink.Netlink.Address
import org.midonet.netlink._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.NanoClock

/**
 * RtnetlinkConnectionProvider provides the interface to generate various
 * RtnetlinkConnection instances which types are specified as its type
 * parameter.
 *
 * @param tag the implicit parameter passed at runtime to avoid type erasure.
 * @tparam T the type of the class derived from RtnetlinkConnection.
 */
class RtnetlinkConnectionFactory[+T <: RtnetlinkConnection]
         (implicit tag: ClassTag[T]) {
    import org.midonet.netlink.NetlinkConnection._

    val log: Logger = LoggerFactory.getLogger(tag.runtimeClass)

    /**
     * Instantiate corresponding RtnetlinkConnection class at runtime.
     *
     * @see http://docs.scala-lang.org/overviews/reflection/overview.html#instantiating-a-type-at-runtime
     *
     * @param addr Netlink address.
     * @param maxPendingRequests the maximum number of Netlink requests.
     * @param maxRequestSize the maximum Netlink request size.
     * @param groups the groups of the Netlink channel to subscribe.
     * @return an instance of the class derives RtnetlinkConnection.
     */
    def apply(addr: Netlink.Address = new Address(0),
              maxPendingRequests: Int = DefaultMaxRequests,
              maxRequestSize: Int = DefaultMaxRequestSize,
              groups: Int = DefaultNetlinkGroup): T = try {
        val channel = Netlink.selectorProvider.openNetlinkSocketChannel(
            NetlinkProtocol.NETLINK_ROUTE, groups)

        if (channel == null) {
            log.error("Error creating a NetlinkChannel. Presumably, " +
                "java.library.path is not set.")
        } else {
            channel.connect(addr)
        }
        val mirror = ru.runtimeMirror(getClass.getClassLoader)
        val clazz = mirror.classSymbol(tag.runtimeClass)
        val classMirror = mirror.reflectClass(clazz)
        val constructor = clazz.toType.decl(
            ru.termNames.CONSTRUCTOR).asMethod
        val constructorMirror = classMirror.reflectConstructor(constructor)
        constructorMirror(channel, maxPendingRequests,
            maxRequestSize, NanoClock.DEFAULT).asInstanceOf[T]
    } catch {
        case ex: Exception =>
            log.error("Error connectin to rtnetlink.")
            throw new RuntimeException(ex)
    }

    def apply(): T = {
        apply(new Address(0), maxPendingRequests = DefaultMaxRequests,
            maxRequestSize = DefaultMaxRequestSize,
            groups = DefaultNetlinkGroup)
    }
}

object RtnetlinkConnection
        extends RtnetlinkConnectionFactory[RtnetlinkConnection]

/**
 * Abstracted interfaces for rtnetlink operations exposed publicly.
 */
trait AbstractRtnetlikConnection {
    def linksList(observer: Observer[Set[Link]]): Unit
    def linksGet(ifindex: Int, observer: Observer[Link]): Unit
    def linksCreate(link: Link, observer: Observer[Link]): Unit
    def linksSetAddr(link: Link, mac: MAC,
                     observer: Observer[Boolean]): Unit
    def linksSet(link: Link, observer: Observer[Boolean]): Unit
    // def linksDel(link: Link, observer: Observer[Boolean]): Unit
    def addrsList(observer: Observer[Set[Addr]]): Unit
    // def addrsGet(ifIndex: Int, observer: Observer[Addr]): Unit =
    def addrsGet(ifIndex: Int, observer: Observer[Set[Addr]]): Unit
    def addrsGet(ifIndex: Int, family: Byte,
                 observer: Observer[Set[Addr]]): Unit
    // def addrsCreate(addr: Addr, observer: Observer[Boolean]): Unit
    // def addrsDel(addr: Addr, observer: Observer[Boolean]): Unit
    def routesList(observer: Observer[Set[Route]]): Unit
    def routesGet(dst: IPv4Addr, observer: Observer[Route]): Unit
    def routesCreate(dst: IPv4Addr, prefix: Int, gw: IPv4Addr, link: Link,
                     observer: Observer[Boolean]): Unit
    // def routesDel(route: Route, observer: Observer[Boolean]): Unit
    def neighsList(observer: Observer[Set[Neigh]]): Unit
}

/**
 * Default implementation of rtnetlink connection.
 *
 * @param channel the channel to be used for reading/writeing requests/replies.
 * @param maxPendingRequests the maximum number of pending requests.
 * @param maxRequestSize the maximum number of requests size.
 * @param clock the clock passed to the broker.
 */
class RtnetlinkConnection(val channel: NetlinkChannel,
                          maxPendingRequests: Int,
                          maxRequestSize: Int,
                          clock: NanoClock)
         extends NetlinkConnection
         with AbstractRtnetlikConnection {
    import org.midonet.netlink.NetlinkConnection._

    override val pid: Int = channel.getLocalAddress.getPid
    override protected val log = LoggerFactory.getLogger(
        "org.midonet.netlink.rtnetlink-conn-" + pid)

    private val protocol = new RtnetlinkProtocol(pid)

    protected val reader = new NetlinkReader(channel)
    protected val writer = new NetlinkBlockingWriter(channel)
    protected val replyBuf =
        BytesUtil.instance.allocateDirect(NetlinkReadBufSize)
    override val requestBroker = new NetlinkRequestBroker(writer, reader,
        maxPendingRequests, maxRequestSize, replyBuf, clock)

    /**
     * ResourceObserver create an observer and call the closure given by the
     * users when onNext is invoked.
     */
    protected object ResourceObserver {
        def apply[T](closure: (T) => Any): ResourceObserver[T] =
            new ResourceObserver[T] {
                override def onNext(r: T): Unit = closure(r)
            }
    }

    /**
     * ResourceObserver ignores onCompleted and onError calls.
     *
     * @tparam T the type of the rtnetlink resources.
     */
    protected abstract class ResourceObserver[T] extends Observer[T] {
        override def onCompleted(): Unit = {}
        override def onError(e: Throwable): Unit = {}
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
        netlinkPayloadObserver(bb2Resource(Link.deserializer)(observer))
    implicit
    def addrObserver(observer: Observer[Addr]): Observer[ByteBuffer] =
        netlinkPayloadObserver(bb2Resource(Addr.deserializer)(observer))
    implicit
    def routeObserver(observer: Observer[Route]): Observer[ByteBuffer] =
        netlinkPayloadObserver(bb2Resource(Route.deserializer)(observer))
    implicit
    def neighObserver(observer: Observer[Neigh]): Observer[ByteBuffer] =
        netlinkPayloadObserver(bb2Resource(Neigh.deserializer)(observer))

    implicit
    def linkSetObserver(observer: Observer[Set[Link]]): Observer[ByteBuffer] =
        netlinkPayloadObserver(bb2ResourceSet(Link.deserializer)(observer))
    implicit
    def addrSetObserver(observer: Observer[Set[Addr]]): Observer[ByteBuffer] =
        netlinkPayloadObserver(bb2ResourceSet(Addr.deserializer)(observer))
    implicit
    def routeSetObserver(observer: Observer[Set[Route]]): Observer[ByteBuffer] =
        netlinkPayloadObserver(bb2ResourceSet(Route.deserializer)(observer))
    implicit
    def neighSetObserver(observer: Observer[Set[Neigh]]): Observer[ByteBuffer] =
        netlinkPayloadObserver(bb2ResourceSet(Neigh.deserializer)(observer))

    implicit
    def booleanObserver(observer: Observer[Boolean]): Observer[ByteBuffer] =
        bb2Resource(AlwaysTrueReader)(observer)

    override def linksList(observer: Observer[Set[Link]]): Unit =
        sendRequest(observer)(protocol.prepareLinkList)

    /* override def linksGet(ifName: String, observer: Observer[Link]): Unit =
        sendRequest(observer)(buf => protocol.prepareLinkGet(buf, )) */

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

    /* override def linksDel(link: Link, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => prepareLinkDel(buf, link) */

    override def addrsList(observer: Observer[Set[Addr]]): Unit =
        sendRequest(observer)(protocol.prepareAddrList)

    // def addrsGet(ifIndex: Int, observer: Observer[Addr]): Unit =
    override def addrsGet(ifIndex: Int, observer: Observer[Set[Addr]]): Unit =
        sendRequest(observer)(buf =>
            protocol.prepareAddrGet(buf, ifIndex, Addr.Family.AF_INET))

    override def addrsGet(ifIndex: Int, family: Byte,
                          observer: Observer[Set[Addr]]): Unit =
        sendRequest(observer)(buf =>
            protocol.prepareAddrGet(buf, ifIndex, family))

    /* def addrsCreate(addr: Addr, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => protocol.prepareAddrCreate(buf, addr))*/

    /* def addrsDel(addr: Addr, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => protocol.prepareAddrDel(buf, addr)) */

    override def routesList(observer: Observer[Set[Route]]): Unit =
        sendRequest(observer)(protocol.prepareRouteList)

    override def routesGet(dst: IPv4Addr, observer: Observer[Route]): Unit =
        sendRequest(observer)(buf => protocol.prepareRouteGet(buf, dst))

    override def routesCreate(dst: IPv4Addr, prefix: Int, gw: IPv4Addr, link: Link,
                     observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf =>
            protocol.prepareRouteNew(buf, dst, prefix, gw, link))

    /* def routesDel(route: Route, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => protocol.prepareRouteDel(buf, route)) */

    override def neighsList(observer: Observer[Set[Neigh]]): Unit =
        sendRequest(observer)(protocol.prepareNeighList)

    /* override def neighsGet(ifName: String, observer: Observer[Neigh]): Unit =
        sendRequest(obsrever)(protocol.prepareNeighGet) */

    /* override def neighsCreate(neigh: Neigh,
                                 observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => protocol.prepareNeighCreate(buf, neight))
    */

    /* override def neighsDel(neigh: Neigh, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => protocol.prepareNeighDel(buf, neight)) */
}
