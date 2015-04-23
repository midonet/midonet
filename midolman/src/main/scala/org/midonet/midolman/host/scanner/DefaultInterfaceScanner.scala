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

import scala.collection.JavaConversions._
import scala.collection.mutable

import com.google.inject.Singleton
import rx.observables.ConnectableObservable
import rx.subjects.{PublishSubject, ReplaySubject}
import rx.{Observable, Observer, Subscription}

import org.midonet.Util
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.netlink._
import org.midonet.netlink.rtnetlink._
import org.midonet.util.concurrent.NanoClock
import org.midonet.util.functors._
import org.midonet.util.reactivex.CompletableObserver

object DefaultInterfaceScanner {
    val NotificationSeq = 0

    def apply() = new DefaultInterfaceScanner(new NetlinkChannelFactory,
            NetlinkUtil.DEFAULT_MAX_REQUESTS,
            NetlinkUtil.DEFAULT_MAX_REQUEST_SIZE,
            NanoClock.DEFAULT)
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
    extends RtnetlinkConnection(
            channelFactory.create(blocking = true,
                NetlinkProtocol.NETLINK_ROUTE, notification = false),
            maxPendingRequests,
            maxRequestSize,
            clock)
        with InterfaceScanner
        with NetlinkNotificationReader {
    import DefaultInterfaceScanner._

    override protected val name = this.getClass.getName + pid

    val capacity = Util.findNextPositivePowerOfTwo(maxPendingRequests)

    override protected val notificationChannel: NetlinkChannel =
        channelFactory.create(blocking = true, NetlinkProtocol.NETLINK_ROUTE,
            notification = true)

    // DefaultInterfaceScanner holds all interface information but it exposes
    // only L2 Ethernet interfaces, interfaces with MAC addresses.
    private val interfaceDescriptions =
        mutable.Map.empty[Int, InterfaceDescription]

    // Mapping from an ifindex to a link.
    private val links = mutable.Map.empty[Int, Link]
    // Mapping from an ifindex to a set of addresses of a link associated with
    // the ifindex.
    private val addrs = mutable.Map.empty[Int, mutable.Set[Addr]]

    private var isSubscribed = false

    private def isVirtual(link: Link): Boolean =
        if (((link.ifi.`type` > Link.Type.ARPHRD_INFINIBAND) &&
            (link.ifi.`type` < Link.Type.ARPHRD_FCPP)) ||
            ((link.ifi.`type` >= Link.Type.ARPHRD_PHONET) &&
                (link.ifi.`type` <= Link.Type.ARPHRD_6LOWPAN))) {
            true
        } else {
            false
        }

    private def linkType(link: Link): InterfaceDescription.Type = {
        val t: Option[InterfaceDescription.Type] =
            link.attributes.toMap.get(Link.NestedAttrKey.IFLA_INFO_KIND) match {
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
                InterfaceDescription.Type.VIRT
        })
    }

    private def linkEndpoint(link: Link): InterfaceDescription.Endpoint = {
        val endpoint: Option[InterfaceDescription.Endpoint] =
            link.attributes.toMap.get(Link.NestedAttrKey.IFLA_INFO_KIND) match {
                case Some(s: String)
                        if s == Link.NestedAttrValue.LinkInfo.KIND_TUN =>
                    Some(InterfaceDescription.Endpoint.TUNTAP)
                case Some(_: String) =>
                    None
                case _ =>
                    if  (link.ifi.`type` == Link.Type.ARPHRD_LOOPBACK) {
                        Some(InterfaceDescription.Endpoint.LOCALHOST)
                    } else {
                        Some(InterfaceDescription.Endpoint.DATAPATH)
                    }
            }
        endpoint.getOrElse(link.ifi.`type` match {
            case Link.Type.ARPHRD_IPGRE | Link.Type.ARPHRD_IP6GRE =>
                InterfaceDescription.Endpoint.GRE
            // Workaround to fit with the current logic of other components.
            case _ =>
                InterfaceDescription.Endpoint.UNKNOWN
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
        val existingInetAddresses = desc.getInetAddresses
        addr.ipv4.foreach { ipv4 =>
            val inetAddr = InetAddress.getByAddress(ipv4.toBytes)
            if (!existingInetAddresses.contains(inetAddr)) {
                desc.setInetAddress(inetAddr)
            }
        }
        addr.ipv6.foreach { ipv6 =>
            val inetAddr = InetAddress.getByName(ipv6.toString)
            if (!existingInetAddresses.contains(inetAddr)) {
                desc.setInetAddress(inetAddr)
            }
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

    private val notificationSubject = ReplaySubject.create[ByteBuffer]()
    override protected val notificationObserver: Observer[ByteBuffer] =
        notificationSubject

    /*
     * Returns a set of interface descriptions where interfaces without MAC
     * addresses are filtered out.
     */
    private def filteredIfDescSet: Set[InterfaceDescription] =
        interfaceDescriptions.values.filter(_.getMac != null).toSet

    private def isAddrNotification(nlType: Short): Boolean = nlType match {
        case Rtnetlink.Type.NEWADDR | Rtnetlink.Type.DELADDR => true
        case _ => false
    }

    /*
     * This exposes interfaces concerned by MidoNet, interfaces with MAC
     * addresses as Observables to Observers subscribing them. Linux interfaces
     * without MAC addresses are filtered out when they're published, but please
     * note they are held internally.
     */
    private
    def makeObs(buf: ByteBuffer): Observable[Set[InterfaceDescription]] = {
        if (buf.remaining() < NetlinkMessage.HEADER_SIZE) {
            return Observable.empty()
        }
        val NetlinkHeader(_, nlType, _, seq, _) =
            NetlinkUtil.readNetlinkHeader(buf)
        if (seq != NotificationSeq && !isAddrNotification(nlType)) {
            Observable.empty()
        } else {
            // Add/update or remove a new entry to/from local data of
            // InterfaceScanner.
            //   http://www.infradead.org/~tgr/libnl/doc/route.html
            nlType match {
                case Rtnetlink.Type.NEWLINK =>
                    log.debug("Received NEWLINK notification")
                    val link = Link.buildFrom(buf)
                    links.get(link.ifi.index) match {
                        case Some(previous: Link) if link == previous =>
                            Observable.empty[Set[InterfaceDescription]]
                        case _ =>
                            links += (link.ifi.index -> link)
                            interfaceDescriptions += (link.ifi.index ->
                                linkToIntefaceDescription(link))
                            Observable.just(filteredIfDescSet)
                    }
                case Rtnetlink.Type.DELLINK =>
                    log.debug("Received DELLINK notification")
                    val link = Link.buildFrom(buf)
                    if (links.containsKey(link.ifi.index)) {
                        links -= link.ifi.index
                        interfaceDescriptions -= link.ifi.index
                        Observable.just(filteredIfDescSet)
                    } else {
                        Observable.empty[Set[InterfaceDescription]]
                    }
                case Rtnetlink.Type.NEWADDR =>
                    log.debug("Received NEWADDR notification")
                    val addr = Addr.buildFrom(buf)
                    addrs.get(addr.ifa.index) match {
                        case Some(addrSet: mutable.Set[Addr])
                                if addrSet.contains(addr) =>
                            Observable.empty[Set[InterfaceDescription]]
                        case _ =>
                            addrs(addr.ifa.index) =
                                addrs.getOrElse(addr.ifa.index,
                                    mutable.Set.empty) + addr
                            interfaceDescriptions += (addr.ifa.index ->
                                addAddr(addr))
                            Observable.just(filteredIfDescSet)
                    }
                case Rtnetlink.Type.DELADDR =>
                    log.debug("Received DELADDR notification")
                    val addr = Addr.buildFrom(buf)
                    addrs.get(addr.ifa.index) match {
                        case Some(addrSet: mutable.Set[Addr])
                                if addrSet.contains(addr) =>
                            addrSet -= addr
                            val descOption = removeAddr(addr)
                            if (descOption.isDefined) {
                                interfaceDescriptions += (addr.ifa.index ->
                                    descOption.get)
                            }
                            Observable.just(filteredIfDescSet)
                        case _ =>
                            Observable.empty[Set[InterfaceDescription]]
                    }
                case t: Short => // Ignore other notifications.
                    log.debug(s"Received a notification with the type $t")
                    Observable.empty()
            }
        }
    }

    private val initialScan = ReplaySubject.create[Set[InterfaceDescription]]

    private
    val notifications: ConnectableObservable[Set[InterfaceDescription]] =
        notificationSubject.flatMap(
            makeFunc1[ByteBuffer, Observable[Set[InterfaceDescription]]] {
                buf =>
                    log.debug("Got the broadcast message from the kernel")
                    makeObs(buf)
            }).mergeWith(initialScan).publish()

    override
    def subscribe(obs: Observer[Set[InterfaceDescription]]): Subscription = {
        val subscription = notifications.subscribe(obs)
        if (!isSubscribed) {
            isSubscribed = true
            notifications.connect()
        }
        // Push the current statuses of interfaces to the observer.
        val currentState: Set[InterfaceDescription] = filteredIfDescSet
        if (currentState.nonEmpty) {
            obs.onNext(filteredIfDescSet)
        }
        subscription
    }

    private def composeIfDesc(links: Set[Link],
                              addrs: Set[Addr]): Set[InterfaceDescription] = {
        links.foreach { link =>
            interfaceDescriptions +=
                (link.ifi.index -> linkToIntefaceDescription(link))
            this.links += (link.ifi.index -> link)
        }
        addrs.foreach { addr =>
            interfaceDescriptions +=
                (addr.ifa.index -> addAddr(addr))
            this.addrs(addr.ifa.index) =
                this.addrs.getOrElse(addr.ifa.index, mutable.Set.empty) + addr
        }
        interfaceDescriptions.values.toSet
    }

    /**
     * Right after starting the read thread, it retrieves the initial link
     * information to prepare for holding the latest state of the links notified
     * by the kernel. There's no guarantee that the notification can't happen
     * before the initial link information retrieval and users of this class
     * should be responsible not to modify any links during this starts.
     */
    override def start(): Unit = {
        notificationReadThread.setDaemon(true)
        notificationReadThread.start()

        log.debug("Retrieving the initial interface information")
        // Netlink requests should be done sequentially one by one. One request
        // should be made per channel. Otherwise you'll get "[16] Resource or
        // device busy".
        // See:
        //    http://lxr.free-electrons.com/source/net/netlink/af_netlink.c#L2732
        val linkListSubject = PublishSubject.create[Set[Link]]
        val addrListSubject = PublishSubject.create[Set[Addr]]

        Observable.zip[Set[Link], Set[Addr], Set[InterfaceDescription]](
            linkListSubject, addrListSubject, makeFunc2((links, addrs) => {
                log.debug(
                    "Composing the initial state from the retrieved data")
                composeIfDesc(links, addrs)
                log.debug("Composed the initial interface descriptions: ",
                    interfaceDescriptions)
                filteredIfDescSet
            })).subscribe(initialScan)

        val linkListObserver =
            new CompletableObserver[Set[Link]](linkListSubject)
        linksList(linkListObserver)
        while (!linkListObserver.isCompleted) {
            try {
                requestBroker.readReply()
            } catch {
                case t: Throwable =>
                    log.error("Error happened on reading rtnetlink messages", t)
            }
        }
        val addrListObserver =
            new CompletableObserver[Set[Addr]](addrListSubject)
        addrsList(addrListObserver)
        while (!addrListObserver.isCompleted) {
            try {
                requestBroker.readReply()
            } catch {
                case t: Throwable =>
                    log.error("Error happened on reading rtnetlink messages", t)
            }
        }
        log.debug("InterfaceScanner has successfully started")
    }

    override def stop(): Unit = {
        notificationReadThread.interrupt()
        notificationChannel.close()
    }
}
