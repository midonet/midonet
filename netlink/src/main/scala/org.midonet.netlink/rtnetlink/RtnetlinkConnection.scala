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

import org.midonet.netlink.NetlinkConnection.DefaultNetlinkGroup
import org.midonet.netlink._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.NanoClock

/**
 * RtnetlinkConnectionProvider provides the interface to generate various
 * RtnetlinkConnection instances which types are specified as its type
 * parameter.
 *
 * @param tag
 * @tparam T
 */
class RtnetlinkConnectionProvider[+T <: RtnetlinkConnection]
         (implicit tag: ClassTag[T]) {

//trait RtnetlinkConnectionProvider {
    // val log: Logger = LoggerFactory.getLogger(classTag[T].runtimeClass)
    val log: Logger = LoggerFactory.getLogger(tag.runtimeClass)
    // val log: Logger

    /**
     * Instanciate corresponding class at runtime.
     *
     * @see http://docs.scala-lang.org/overviews/reflection/overview.html#instantiating-a-type-at-runtime
     *
     * @param addr Netlink address
     * @param sendPool buffer pool used for sending
     * @param group group ID of the Netlink channel
     * @return an instance of the class derives RtnetlinkConnection
     */
    def apply(addr: Netlink.Address, sendPool: BufferPool,
              group: Int = DefaultNetlinkGroup): T = try {
        val channel = Netlink.selectorProvider.openNetlinkSocketChannel(
            NetlinkProtocol.NETLINK_ROUTE, group)

        if (channel == null) {
            log.error("Error creating a NetlinkChannel. Presumably, " +
                "java.library.path is not set.")
        } else {
            channel.connect(addr)
        }
        val mirror = ru.runtimeMirror(getClass.getClassLoader)
        // val clazz = ru.typeOf[T].typeSymbol.asClass
        val clazz = mirror.classSymbol(tag.runtimeClass)
        val classMirror = mirror.reflectClass(clazz)
        val constructor = clazz.toType.decl(
            ru.termNames.CONSTRUCTOR).asMethod
        val constructorMirror = classMirror.reflectConstructor(constructor)
        constructorMirror(channel, sendPool, NanoClock.DEFAULT).asInstanceOf[T]
    } catch {
        case ex: Exception =>
            log.error("Error connectin to rtnetlink.")
            throw new RuntimeException(ex)
    }
}

object RtnetlinkConnection
        extends RtnetlinkConnectionProvider[RtnetlinkConnection]


/*object RtnetlinkConnection {
    val log: Logger = LoggerFactory.getLogger(classOf[RtnetlinkConnection])

    def apply(addr: Netlink.Address, sendPool: BufferPool, groups: Int) = try {
        val channel = Netlink.selectorProvider.openNetlinkSocketChannel(
            NetlinkProtocol.NETLINK_ROUTE, groups)

        if (channel == null) {
            log.error("Error creating a NetlinkChannel. Presumably, " +
                "java.library.path is not set.")
        } else {
            channel.connect(addr)
        }
        new RtnetlinkConnection(channel, sendPool, new SystemNanoClock)
    } catch {
        case ex: Exception =>
            log.error("Error connectin to rtnetlink.")
            throw new RuntimeException(ex)
    }
}*/

class RtnetlinkConnection(val channel: NetlinkChannel,
                          sendPool: BufferPool,
                          clock: NanoClock) extends NetlinkConnection {
    import org.midonet.netlink.NetlinkConnection._

    override val pid: Int = channel.getLocalAddress.getPid
    override protected val log = LoggerFactory.getLogger(
        "org.midonet.netlink.rtnetlink-conn-" + pid)
    override protected val requestPool = sendPool

    private val protocol = new RtnetlinkProtocol(pid)

    protected val reader = new NetlinkReader(channel)
    protected val writer = new NetlinkBlockingWriter(channel)
    protected val replyBuf =
        BytesUtil.instance.allocateDirect(NetlinkReadBufSize)
    override val requestBroker = new NetlinkRequestBroker(reader, writer,
        MaxRequests, replyBuf, clock, timeout = DefaultTimeout)

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

    def linksList(observer: Observer[Set[Link]]): Unit =
        sendRequest(observer)(protocol.prepareLinkList)

/*    def linksGet(ifName: String, observer: Observer[Link]): Unit =
        sendRequest(observer)(buf => protocol.prepareLinkGet(buf, ))*/

    def linksGet(ifindex: Int, observer: Observer[Link]): Unit =
        sendRequest(observer)(buf => protocol.prepareLinkGet(buf, ifindex))

    def linksCreate(link: Link, observer: Observer[Link]): Unit =
        sendRequest(observer)(buf => protocol.prepareLinkCreate(buf, link))

    def linksSetAddr(link: Link, mac: MAC,
                     observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf =>
            protocol.prepareLinkSetAddr(buf, link, mac))

    def linksSet(link: Link, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => protocol.prepareLinkSet(buf, link))

/*    def linksDel(link: Link, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => /* prepareLinkDel(buf, link */)*/

    def addrsList(observer: Observer[Set[Addr]]): Unit =
        sendRequest(observer)(protocol.prepareAddrList)

    // def addrsGet(ifIndex: Int, observer: Observer[Addr]): Unit =
    def addrsGet(ifIndex: Int, observer: Observer[Set[Addr]]): Unit =
        sendRequest(observer)(buf =>
            protocol.prepareAddrGet(buf, ifIndex, Addr.Family.AF_INET))

    def addrsGet(ifIndex: Int, family: Byte,
                 observer: Observer[Set[Addr]]): Unit =
        sendRequest(observer)(buf =>
            protocol.prepareAddrGet(buf, ifIndex, family))

/*    def addrsCreate(addr: Addr, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => /* protocol.prepareAddrCreate(buf, addr) */)*/

/*    def addrsDel(addr: Addr, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => protocol.prepareAddrDel(buf, addr))*/

    def routesList(observer: Observer[Set[Route]]): Unit =
        sendRequest(observer)(protocol.prepareRouteList)

    def routesGet(dst: IPv4Addr, observer: Observer[Route]): Unit =
        sendRequest(observer)(buf => protocol.prepareRouteGet(buf, dst))

    def routesCreate(dst: IPv4Addr, prefix: Int, gw: IPv4Addr, link: Link,
                     observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf =>
            protocol.prepareRouteNew(buf, dst, prefix, gw, link))

/*    def routesDel(route: Route, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => /* protocol.prepareRouteDel(buf, route) */)*/

    def neighsList(observer: Observer[Set[Neigh]]): Unit =
        sendRequest(observer)(protocol.prepareNeighList)
/*
    def neighsGet(ifName: String, observer: Observer[Neigh]): Unit =
        sendRequest(obsrever)(protocol.prepareNeighGet)

    def neighsCreate(neigh: Neigh, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => /* protocol.prepareNeighCreate(buf, neight) */)

    def neighsDel(neigh: Neigh, observer: Observer[Boolean]): Unit =
        sendRequest(observer)(buf => /* protocol.prepareNeighDel(buf, neight) */)*/
}
