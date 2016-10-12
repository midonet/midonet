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
import java.nio.channels.{AsynchronousCloseException, ClosedByInterruptException, ClosedChannelException}
import java.util

import org.midonet.packets.MAC
import scala.collection.JavaConversions._
import scala.collection.mutable

import rx.observables.ConnectableObservable
import rx.subjects.{BehaviorSubject, PublishSubject}
import rx.{Observable, Observer, Scheduler, Subscription}

import org.midonet.Util
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.netlink._
import org.midonet.netlink.rtnetlink._
import org.midonet.util.concurrent.NanoClock
import org.midonet.util.functors._
import org.midonet.util.reactivex.RequestObserver

object DefaultInterfaceScanner {
    val NotificationSeq = 0
    private val channelFactory = new NetlinkChannelFactory

    def apply() = new DefaultInterfaceScanner(channelFactory,
            NetlinkUtil.DEFAULT_MAX_REQUESTS,
            NetlinkUtil.DEFAULT_MAX_REQUEST_SIZE,
            NanoClock.DEFAULT)
}

/**
 * InterfaceScanner watches the link stats of the host and updates their
 * information accordingly when they changed.
 *
 * @param channelFactory the factory class provides NetlinkChannel.
 * @param maxPendingRequests the maximum number of pending requests.
 * @param maxRequestSize the maximum size of Netlink requests.
 * @param clock the clock given to the broker.
 */
class DefaultInterfaceScanner(channelFactory: NetlinkChannelFactory,
                              maxPendingRequests: Int,
                              maxRequestSize: Int,
                              clock: NanoClock)
    extends RtnetlinkConnection(
            channelFactory.create(blocking = true,
                NetlinkProtocol.NETLINK_ROUTE,
                notificationGroups = NetlinkUtil.NO_NOTIFICATION),
            maxPendingRequests,
            maxRequestSize,
            clock)
        with InterfaceScanner {
    import DefaultInterfaceScanner._

    private val name = this.getClass.getName + pid

    val capacity = Util.findNextPositivePowerOfTwo(maxPendingRequests)

    private val notificationChannel: NetlinkChannel =
        channelFactory.create(blocking = true, NetlinkProtocol.NETLINK_ROUTE,
            notificationGroups = NetlinkUtil.DEFAULT_RTNETLINK_GROUPS)
    private val notificationReader = new NetlinkReader(notificationChannel)
    private val notificationSubject = BehaviorSubject.create[ByteBuffer]()

    private class ErrorReporter[T] extends Observer[T] {
        override def onCompleted(): Unit = {}
        override def onError(t: Throwable): Unit =
            log.error("Error occurred on reading notifications", t)
        override def onNext(r : T): Unit = {}
    }
    notificationSubject.subscribe(new ErrorReporter[ByteBuffer])

    private
    val rtnetlinkNotificationReadThread = new Thread(s"$name-notification") {
        override def run(): Unit = try {
            NetlinkUtil.readNetlinkNotifications(notificationChannel,
                notificationReader, NetlinkMessage.HEADER_SIZE,
                notificationSubject)
        }  catch {
            case ex @ (_: InterruptedException |
                       _: ClosedChannelException |
                       _: ClosedByInterruptException|
                       _: AsynchronousCloseException) =>
                log.info(s"$ex on rtnetlink notification channel, STOPPING")
                notificationSubject.onCompleted()
            case ex: Exception =>
                log.error(s"$ex on rtnetlink notification channel, ABORTING",
                    ex)
                notificationSubject.onError(ex)
        }
    }

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

    private def linkType(link: Link): InterfaceDescription.Type =
        link.ifi.`type` match {
            case Link.Type.ARPHRD_ETHER |
                 Link.Type.ARPHRD_EETHER |
                 Link.Type.ARPHRD_IEEE802 |
                 Link.Type.ARPHRD_DLCI |
                 Link.Type.ARPHRD_ATM |
                 Link.Type.ARPHRD_IEEE1394 |
                 Link.Type.ARPHRD_X25 |
                 Link.Type.ARPHRD_FDDI |
                 Link.Type.ARPHRD_FCPP |
                 Link.Type.ARPHRD_FCAL |
                 Link.Type.ARPHRD_FCPL |
                 Link.Type.ARPHRD_FCFABRIC |
                 Link.Type.ARPHRD_IEEE80211 =>
                InterfaceDescription.Type.PHYS
            case Link.Type.ARPHRD_NETROM |
                 Link.Type.ARPHRD_LOOPBACK =>
                InterfaceDescription.Type.VIRT
            case Link.Type.ARPHRD_TUNNEL |
                 Link.Type.ARPHRD_TUNNEL6 |
                 Link.Type.ARPHRD_IPDDP |
                 Link.Type.ARPHRD_IPGRE |
                 Link.Type.ARPHRD_IP6GRE =>
                InterfaceDescription.Type.TUNN
            case _ =>
                InterfaceDescription.Type.UNKNOWN
        }

    private def linkEndpoint(link: Link): InterfaceDescription.Endpoint = {
        val endpoint: Option[InterfaceDescription.Endpoint] =
            link.info.kind match {
                case Link.NestedAttrValue.LinkInfo.KIND_TUN =>
                    Some(InterfaceDescription.Endpoint.TUNTAP)
                case null =>
                    if  (link.ifi.`type` == Link.Type.ARPHRD_LOOPBACK) {
                        Some(InterfaceDescription.Endpoint.LOCALHOST)
                    } else {
                        Some(InterfaceDescription.Endpoint.DATAPATH)
                    }
                case _ =>
                    None
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
        val clone = cloneIfDesc(desc)
        clone.setName(link.getName)
        clone.setType(linkType(link))
        clone.setMac(link.mac)
        clone.setUp((link.ifi.flags & Link.Flag.IFF_UP) == 1)
        clone.setHasLink(link.link != link.ifi.index)
        clone.setMtu(link.mtu)
        clone.setEndpoint(linkEndpoint(link))
        clone
    }

    private def linkToIntefaceDescription(link: Link): InterfaceDescription = {
        val descOption: Option[InterfaceDescription] =
            interfaceDescriptions.get(link.ifi.index)
        val desc = descOption.getOrElse(new InterfaceDescription(
            link.getName, link.ifi.index))
        linkToDesc(link, desc)
    }

    private def addrToDesc(addr: Addr,
                           desc: InterfaceDescription): InterfaceDescription = {
        val clone = cloneIfDesc(desc)
        val existingInetAddresses = clone.getInetAddresses
        addr.ipv4.foreach { ipv4 =>
            val inetAddr = InetAddress.getByAddress(ipv4.toBytes)
            if (!existingInetAddresses.contains(inetAddr)) {
                clone.setInetAddress(inetAddr)
            }
        }
        addr.ipv6.foreach { ipv6 =>
            val inetAddr = InetAddress.getByName(ipv6.toString)
            if (!existingInetAddresses.contains(inetAddr)) {
                clone.setInetAddress(inetAddr)
            }
        }
        clone
    }

    private def addAddr(addr: Addr): InterfaceDescription = {
        val descOption: Option[InterfaceDescription] =
            interfaceDescriptions.get(addr.ifa.index)
        val desc = descOption.getOrElse(
            new InterfaceDescription(addr.ifa.index.toString,
                                     addr.ifa.index))
        addrToDesc(addr, desc)
    }

    private
    def removeAddr(addr: Addr): Option[InterfaceDescription] = {
        interfaceDescriptions.get(addr.ifa.index) map { ifdesc =>
            val clone = cloneIfDesc(ifdesc)
            addr.ipv4.foreach(ipv4 =>
                clone.getInetAddresses.remove(
                    InetAddress.getByAddress(ipv4.toBytes)))
            addr.ipv6.foreach(ipv6 =>
                clone.getInetAddresses.remove(
                    InetAddress.getByName(ipv6.toString)))
            clone
        }
    }

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
    def toObservable(buf: ByteBuffer): Observable[Set[InterfaceDescription]] = {
        val seq = buf.getInt(NetlinkMessage.NLMSG_SEQ_OFFSET)
        val nlType = buf.getShort(NetlinkMessage.NLMSG_TYPE_OFFSET)
        if (seq != NotificationSeq && !isAddrNotification(nlType)) {
            Observable.empty()
        } else {
            buf.position(NetlinkMessage.HEADER_SIZE)
            // Add/update or remove a new entry to/from local data of
            // InterfaceScanner.
            //   http://www.infradead.org/~tgr/libnl/doc/route.html
            nlType match {
                case Rtnetlink.Type.NEWLINK =>
                    log.trace("Received NEWLINK notification")
                    val link = Link.buildFrom(buf)
                    links.get(link.ifi.index) match {
                        case Some(previous: Link) if link == previous =>
                            Observable.empty[Set[InterfaceDescription]]
                        case _ =>
                            log.debug("Received NEWLINK notification with a " +
                                          s"new link $link")
                            links += (link.ifi.index -> link)
                            interfaceDescriptions += (link.ifi.index ->
                                linkToIntefaceDescription(link))
                            Observable.just(filteredIfDescSet)
                    }
                case Rtnetlink.Type.DELLINK =>
                    log.trace("Received DELLINK notification")
                    val link = Link.buildFrom(buf)
                    if (links.containsKey(link.ifi.index)) {
                        log.debug("Received DELLINK notification with the " +
                                      s"existing link $link")
                        links -= link.ifi.index
                        interfaceDescriptions -= link.ifi.index
                        Observable.just(filteredIfDescSet)
                    } else {
                        Observable.empty[Set[InterfaceDescription]]
                    }
                case Rtnetlink.Type.NEWADDR =>
                    log.trace("Received NEWADDR notification")
                    val addr = Addr.buildFrom(buf)
                    if (!interfaceDescriptions.containsKey(addr.ifa.index)) {
                        addrs -= addr.ifa.index
                        Observable.empty[Set[InterfaceDescription]]
                    } else {

                        addrs.get(addr.ifa.index) match {
                            case Some(addrSet: mutable.Set[Addr])
                                if addrSet.contains(addr) =>
                                Observable.empty[Set[InterfaceDescription]]
                            case _ =>
                                log.debug("Received NEWADDR notification " +
                                    "with a new address")
                                addrs(addr.ifa.index) =
                                    addrs.getOrElse(addr.ifa.index,
                                        mutable.Set.empty) + addr
                                interfaceDescriptions += (addr.ifa.index ->
                                    addAddr(addr))
                                Observable.just(filteredIfDescSet)
                        }
                    }
                case Rtnetlink.Type.DELADDR =>
                    log.trace("Received DELADDR notification")
                    val addr = Addr.buildFrom(buf)
                    if (!interfaceDescriptions.containsKey(addr.ifa.index)) {
                        addrs -= addr.ifa.index
                        Observable.empty[Set[InterfaceDescription]]
                    } else {
                        addrs.get(addr.ifa.index) match {
                            case Some(addrSet: mutable.Set[Addr])
                                if addrSet.contains(addr) =>
                                log.debug("Received DELADDR notification " +
                                    "with the existing address")
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
                    }
                case t: Short => // Ignore other notifications.
                    log.trace(s"Received a notification with the type $t")
                    Observable.empty()
            }
        }
    }

    private val initialScan = BehaviorSubject.create[Set[InterfaceDescription]]

    private
    val notifications: ConnectableObservable[Set[InterfaceDescription]] =
        notificationSubject.flatMap(
            makeFunc1[ByteBuffer, Observable[Set[InterfaceDescription]]] {
                buf => try {
                    log.trace("Got a notification from the kernel")
                    toObservable(buf)
                } catch {
                    case ex: Exception =>
                        log.error("Error occurred on composing interface" +
                            "descriptions", ex)
                        Observable.empty[Set[InterfaceDescription]]
                }
            }).mergeWith(initialScan).publish()
    notifications.subscribe(new ErrorReporter[Set[InterfaceDescription]])

    override
    def subscribe(obs: Observer[Set[InterfaceDescription]],
                  scheduler: Option[Scheduler] = None): Subscription = {
        val subscription = scheduler match {
            case Some(sched) => notifications.observeOn(sched).subscribe(obs)
            case None => notifications.subscribe(obs)
        }

        if (!isSubscribed) {
            isSubscribed = true
            notifications.connect()
        }
        // Push the current statuses of interfaces to the observer.
        val currentState: Set[InterfaceDescription] = filteredIfDescSet
        if (currentState.nonEmpty) {
            obs.onNext(currentState)
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

    private def cloneIfDesc(ifdesc: InterfaceDescription): InterfaceDescription = {
        val clone = new InterfaceDescription(ifdesc.getName, ifdesc.getIfindex)
        clone.setEndpoint(ifdesc.getEndpoint)
        ifdesc.getInetAddresses foreach clone.setInetAddress
        clone.setHasLink(ifdesc.hasLink)
        if (ifdesc.getMac eq null) {
            clone.setMac(null.asInstanceOf[MAC])
        } else {
            clone.setMac(MAC.fromAddress(ifdesc.getMac.getAddress))
        }
        clone.setMtu(ifdesc.getMtu)
        clone.setPortType(ifdesc.getPortType)
        clone.setType(ifdesc.getType)
        clone.setProperties(new util.HashMap(ifdesc.getProperties))
        clone.setUp(ifdesc.isUp)
        clone
    }

    /**
     * Right after starting the read thread, it retrieves the initial link
     * information to prepare for holding the latest state of the links notified
     * by the kernel. There's no guarantee that the notification can't happen
     * before the initial link information retrieval and users of this class
     * should be responsible not to modify any links during this starts.
     */
    override def start(): Unit = {
        rtnetlinkNotificationReadThread.setDaemon(true)
        rtnetlinkNotificationReadThread.start()

        log.debug("Retrieving the initial interface information")
        // Netlink dump requests should be done sequentially one by one. One
        // request should be made per channel. Otherwise you'll get "[16]
        // Resource or device busy".
        // See:
        //    http://lxr.free-electrons.com/source/net/netlink/af_netlink.c?v=4.0#L2732
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

        val linkListRequestObserver = RequestObserver(linkListSubject)
        linksList(linkListSubject)
        while (!linkListRequestObserver.isCompleted) {
            try {
                requestBroker.readReply()
            } catch {
                case e: Exception =>
                    log.error("Error occurred on listing links", e)
            }
        }
        val addrListRequestObserver = RequestObserver(addrListSubject)
        addrsList(addrListSubject)
        while (!addrListRequestObserver.isCompleted) {
            try {
                requestBroker.readReply()
            } catch {
                case e: Exception =>
                    log.error("Error occurred on listing addresses", e)
            }
        }
        log.debug("InterfaceScanner has successfully started")
    }

    override def stop(): Unit = {
        rtnetlinkNotificationReadThread.interrupt()
        notificationChannel.close()
    }
}
