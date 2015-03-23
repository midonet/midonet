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

package org.midonet.midolman.host.scanner

import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.{Set => JSet}

import scala.collection.JavaConversions._
import scala.collection.concurrent.TrieMap

import com.google.inject.Singleton
import rx.subjects.{AsyncSubject, ReplaySubject}
import rx.{Observable, Observer, Subscription}

import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.netlink.NetlinkConnection._
import org.midonet.netlink._
import org.midonet.netlink.rtnetlink._
import org.midonet.odp.DpPort
import org.midonet.util.concurrent.NanoClock
import org.midonet.util.functors._

object DefaultInterfaceScanner extends
        RtnetlinkConnectionFactory[DefaultInterfaceScanner] {
    val NotificationSeq = 0

    override def apply() = {
        val conn = super.apply()
        conn.start()
        conn
    }
}

/**
 * InterfaceScanner watches the link stats of the host and updates information
 * of them accordingly when they changed.
 *
 * @param channelFactory the factory class provides NetlinkChannel.
 * @param maxPendingRequests the maximum number of pending requests.
 * @param maxRequestSize the maximum size of Netlink requests.
 * @param clock the clock given to the broker.
 */
@Singleton
class DefaultInterfaceScanner(channelFactory: NetlinkChannelFactory,
                              maxPendingRequests: Int,
                              maxRequestSize: Int,
                              clock: NanoClock)
        extends SelectorBasedRtnetlinkConnection(
            channelFactory.create(blocking = true,
                NetlinkProtocol.NETLINK_ROUTE),
            maxPendingRequests,
            maxRequestSize,
            clock)
        with InterfaceScanner {

    import DefaultInterfaceScanner._

    private val linkList: TrieMap[Int, Link] = TrieMap.empty
    private val addressList: TrieMap[Int, Addr] = TrieMap.empty
    private val routeList: TrieMap[Int, Route] = TrieMap.empty
    private val neighList: TrieMap[Int, Neigh] = TrieMap.empty

    private val interfaceDescriptions: TrieMap[Int, InterfaceDescription] =
        TrieMap.empty

    private def linkType(link: Link): InterfaceDescription.Type = {
        val t: Option[InterfaceDescription.Type] =
            link.attributes.toMap.get(Link.NestedAttrKey.IFLA_INFO_KIND) match {
                case Some(s: String)
                        if s == Link.NestedAttrValue.LinkInfo.KIND_TUN =>
                    Some(InterfaceDescription.Type.TUNN)
                case Some(_: String) =>
                    Some(InterfaceDescription.Type.VIRT)
                case _ =>
                    None
            }
        t.getOrElse(link.ifi.`type` match {
            case Link.Type.ARPHRD_LOOPBACK =>
                InterfaceDescription.Type.VIRT
            case Link.Type.ARPHRD_NONE | Link.Type.ARPHRD_VOID =>
                InterfaceDescription.Type.UNKNOWN
            case _ =>
                InterfaceDescription.Type.PHYS
        })
    }

    private def linkEndpoint(link: Link): InterfaceDescription.Endpoint = {
        val endpoint: Option[InterfaceDescription.Endpoint] =
            link.attributes.toMap.get(Link.NestedAttrKey.IFLA_INFO_KIND) match {
                case Some(s: String)
                        if s == Link.NestedAttrValue.LinkInfo.KIND_TUN =>
                    Some(InterfaceDescription.Endpoint.TUNTAP)
                case _ =>
                    None
            }
        endpoint.getOrElse(link.ifi.`type` match {
            case Link.Type.ARPHRD_LOOPBACK =>
                InterfaceDescription.Endpoint.LOCALHOST
            case Link.Type.ARPHRD_IPGRE | Link.Type.ARPHRD_IP6GRE =>
                InterfaceDescription.Endpoint.GRE
            case Link.Type.ARPHRD_NONE | Link.Type.ARPHRD_VOID =>
                InterfaceDescription.Endpoint.UNKNOWN
            case _ =>
                InterfaceDescription.Endpoint.PHYSICAL
        })
    }

    private def linkToDesc(link: Link,
                           desc: InterfaceDescription): InterfaceDescription = {
        desc.setName(link.ifname)
        desc.setType(linkType(link))
        desc.setMac(link.mac)
        desc.setUp((link.ifi.flags & Link.Flag.IFF_UP) == 1)
        desc.setHasLink(link.link != link.ifi.index)
        desc.setMtu(link.mtu)
        desc.setEndpoint(linkEndpoint(link))
        // desc.setPortType(DpPort.Type.NetDev) // Dummy
        desc
    }

    private def linkToIntefaceDescription(link: Link): InterfaceDescription = {
        val descOption: Option[InterfaceDescription] =
            interfaceDescriptions.get(link.ifi.index)
        val desc = descOption.getOrElse(new InterfaceDescription(link.ifname))
        linkToDesc(link, desc)
    }

    private def addrToDesc(addr: Addr,
                           desc: InterfaceDescription): InterfaceDescription = {
        addr.ipv4.foreach { ipv4 =>
            val inetAddr = InetAddress.getByAddress(ipv4.toBytes)
            desc.setInetAddress(inetAddr)
        }
        addr.ipv6.foreach { ipv6 =>
            val inetAddr = InetAddress.getByName(ipv6.toString)
            desc.setInetAddress(inetAddr)
        }
        desc
    }

    private def addAddr(addr: Addr): InterfaceDescription = {
        val descOption: Option[InterfaceDescription] =
            interfaceDescriptions.get(addr.ifa.index)
        val desc = descOption.getOrElse(
            new InterfaceDescription(addr.ifa.index.toString))
        addrToDesc(addr, desc)
        desc
    }

    private
    def removeAddr(addr: Addr): Option[InterfaceDescription] = {
        for (desc <- interfaceDescriptions.get(addr.ifa.index))
        yield {
            addr.ipv4.foreach(ipv4 =>
                desc.getInetAddresses.remove(
                    InetAddress.getByAddress(ipv4.toBytes)))
            addr.ipv6.foreach(ipv6 =>
                desc.getInetAddresses.remove(
                    InetAddress.getByName(ipv6.toString)))
            desc
        }
    }

    /**
     * Get the current links of the host.
     *
     * @return A map from the link index to the Link instance.
     */
    def links: Map[Int, Link] = linkList.toMap

    /**
     * Get the current addresses of the host.
     *
     * @return A map.
     */
    def addresses: Map[Int, Addr] = addressList.toMap

    /**
     * Get the current routes of the host.
     *
     * @return A map.
     */
    def routes: Map[Int, Route] = routeList.toMap

    /**
     * Get the current neighbours of the host.
     *
     * @return A map.
     */
    def neighbours: Map[Int, Neigh] = neighList.toMap

    private val notificationSubject = ReplaySubject.create[ByteBuffer]()

    private
    def makeObs(buf: ByteBuffer): Observable[Set[InterfaceDescription]] = {
        val NetlinkHeader(_, nlType, _, seq, _) =
            NetlinkConnection.readNetlinkHeader(buf)
        if (seq != NotificationSeq) {
            Observable.empty()
        } else {
            // Add/update or remove a new entry to/from local data
            // of InterfaceScanner.
            //   http://www.infradead.org/~tgr/libnl/doc/route.html
            nlType match {
                case Rtnetlink.Type.NEWLINK =>
                    val link = Link.buildFrom(buf)
                    interfaceDescriptions += (link.ifi.index ->
                        linkToIntefaceDescription(link))
                    Observable.just(interfaceDescriptions.values.toSet)
                case Rtnetlink.Type.DELLINK =>
                    val link = Link.buildFrom(buf)
                    interfaceDescriptions -= link.ifi.index
                    Observable.just(interfaceDescriptions.values.toSet)
                case Rtnetlink.Type.NEWADDR =>
                    val addr = Addr.buildFrom(buf)
                    interfaceDescriptions += (addr.ifa.index ->
                        addAddr(addr))
                    Observable.just(interfaceDescriptions.values.toSet)
                case Rtnetlink.Type.DELADDR =>
                    val addr = Addr.buildFrom(buf)
                    val descOption = removeAddr(addr)
                    if (descOption.isDefined) {
                        interfaceDescriptions += (addr.ifa.index ->
                            descOption.get)
                    }
                    Observable.just(interfaceDescriptions.values.toSet)
                /*                            case Rtnetlink.Type.NEWROUTE =>
                        val route = Route.buildFrom(buf)
                        None
                    case Rtnetlink.Type.DELROUTE =>
                        val route = Route.buildFrom(buf)
                        None
                    case Rtnetlink.Type.NEWNEIGH =>
                        val neigh = Neigh.buildFrom(buf)
                        None
                    case Rtnetlink.Type.DELNEIGH =>
                        val neigh = Neigh.buildFrom(buf)
                        None*/
                case _ => // Ignore other notifications.
                    Observable.empty()
            }
        }
    }

    private val notifications: Observable[Set[InterfaceDescription]] =
        notificationSubject.flatMap(
            makeFunc1[ByteBuffer, Observable[Set[InterfaceDescription]]](buf =>
                makeObs(buf)))

    /**
     * The observer to react to the notification messages sent from the kernel.
     * onNext method of this observer is called every time the notification is
     * received in the read thread.
     */
    override val notificationObserver = notificationSubject

    override
    def subscribe(obs: Observer[Set[InterfaceDescription]]): Subscription =
        notifications.subscribe(obs)


    private def composeIfDesc(links: Set[Link],
                              addrs: Set[Addr]): Set[InterfaceDescription] = {
        links.foreach(link =>
            interfaceDescriptions +=
                (link.ifi.index -> linkToIntefaceDescription(link)))
        addrs.foreach(addr =>
            interfaceDescriptions +=
                (addr.ifa.index -> addAddr(addr)))
        interfaceDescriptions.values.toSet
    }

    /**
     * Right after starting the read thread, it retrieves the initial link
     * information to prepare for holding the latest state of the links notified
     * by the kernel. There's not guarantee that the notification can't happen
     * before the initial link information retrieval and users of this class
     * should be responsible not to modify any links during this starts.
     */
    override def start(): Unit = {
        super.start()
        val linkSubject = AsyncSubject.create[Set[Link]]()
        val addrSubject = AsyncSubject.create[Set[Addr]]()
        linksList(linkSubject)
        addrsList(addrSubject)
        Observable.zip[Set[Link], Set[Addr], Set[InterfaceDescription]](
            linkSubject, addrSubject, makeFunc2((links, addrs) =>
                composeIfDesc(links, addrs)))
    }

    override def stop(): Unit = super.stop()
}
