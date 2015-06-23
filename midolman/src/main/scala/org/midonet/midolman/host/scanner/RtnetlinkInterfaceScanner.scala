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
import java.nio.{BufferOverflowException, ByteBuffer}
import java.nio.channels.{ClosedChannelException, AsynchronousCloseException, ClosedByInterruptException}
import java.util.HashMap

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.util.control.NonFatal

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import rx.subjects.PublishSubject
import rx.{Observer, Subscription}

import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner._
import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.rtnetlink._
import org.midonet.util.concurrent.NanoClock
import org.midonet.util.reactivex.AwaitableObserver

/**
 * InterfaceScanner implementation that used rtnetlink to receive updates.
 */
class RtnetlinkInterfaceScanner(channelFactory: NetlinkChannelFactory)
    extends InterfaceScanner {

    private val notificationChannel = channelFactory.create(
            blocking = true,
            protocol = NetlinkProtocol.NETLINK_ROUTE,
            notificationGroups = NetlinkUtil.DEFAULT_RTNETLINK_GROUPS)

    private val pid: Int = notificationChannel.getLocalAddress.getPid

    private val log = Logger(LoggerFactory.getLogger(
        s"org.midonet.netlink.interface-scanner-$pid"))

    private val protocol = new RtnetlinkProtocol(pid)

    private val interfaceDescriptions = new HashMap[Int, InterfaceDescription]()
    private val links = new HashMap[Int, Link]()

    private val notifications = PublishSubject.create[InterfaceOp]()

    private val notificationThread = new Thread(s"interface-scanner-$pid") {
        override def run(): Unit = {
            val notificationReader = new NetlinkReader(notificationChannel)
            val notificationReadBuf =
                BytesUtil.instance.allocate(NetlinkUtil.NETLINK_READ_BUF_SIZE)
            while (notificationChannel.isOpen) {
                try {
                    notificationReadBuf.clear()
                    notificationReader.read(notificationReadBuf)
                    notificationReadBuf.flip()
                    parseAndEmit(notificationReadBuf)
                }  catch {
                    case ignored @ (_: InterruptedException |
                                    _: ClosedChannelException |
                                    _: ClosedByInterruptException|
                                    _: AsynchronousCloseException |
                                    _: BufferOverflowException) =>
                    case NonFatal(e) =>
                        log.error("Unexpected error in notification channel", e)
                }
            }
            log.info(s"Stopping rtnetlink notification channel")
            notifications.onCompleted()
        }
    }

    init()

    private def linkType(link: Link): InterfaceDescription.Type =
        link.ifi.`type` match {
            case Link.Type.ARPHRD_NONE | Link.Type.ARPHRD_VOID =>
                InterfaceDescription.Type.UNKNOWN
            case _ =>
                InterfaceDescription.Type.VIRT
        }

    private def linkEndpoint(link: Link): InterfaceDescription.Endpoint =
        link.attributes.get(Link.NestedAttrKey.IFLA_INFO_KIND) match {
            case s: String if s == Link.NestedAttrValue.LinkInfo.KIND_TUN =>
                InterfaceDescription.Endpoint.TUNTAP
            case _: String =>
                link.ifi.`type` match {
                    case Link.Type.ARPHRD_IPGRE | Link.Type.ARPHRD_IP6GRE =>
                        InterfaceDescription.Endpoint.GRE
                    case _ =>
                        // Workaround to fit with the current logic of other components.
                        InterfaceDescription.Endpoint.UNKNOWN
                }
            case _ =>
                if  (link.ifi.`type` == Link.Type.ARPHRD_LOOPBACK) {
                    InterfaceDescription.Endpoint.LOCALHOST
                } else {
                    InterfaceDescription.Endpoint.DATAPATH
                }
        }

    private def describeAddress(addr: Addr, desc: InterfaceDescription): Boolean = {
        val existingInetAddresses = desc.getInetAddresses
        var updated = false
        addr.ipv4.foreach { ipv4 =>
            val inetAddr = InetAddress.getByAddress(ipv4.toBytes)
            if (!existingInetAddresses.contains(inetAddr)) {
                desc.setInetAddress(inetAddr)
                updated = true
            }
        }
        addr.ipv6.foreach { ipv6 =>
            val inetAddr = InetAddress.getByName(ipv6.toString)
            if (!existingInetAddresses.contains(inetAddr)) {
                desc.setInetAddress(inetAddr)
                updated = true
            }
        }
        updated
    }

    private def getOrCreate(idx: Int): InterfaceDescription = {
        var desc = interfaceDescriptions.get(idx)
        if (desc eq null) {
            desc = new InterfaceDescription()
            interfaceDescriptions.put(idx, desc)
        }
        desc
    }

    private def linkToDesc(link: Link): InterfaceOp = {
        val desc = getOrCreate(link.ifi.index)
        if (link != links.get(link.ifi.index)) {
            links.put(link.ifi.index, link)
            desc.setName(link.ifname)
            desc.setType(linkType(link))
            desc.setMac(link.mac)
            desc.setUp((link.ifi.flags & Link.Flag.IFF_UP) == 1)
            desc.setHasLink(link.link != link.ifi.index)
            desc.setMtu(link.mtu)
            desc.setEndpoint(linkEndpoint(link))
            InterfaceUpdated(desc)
        } else {
            NoOp
        }
    }

    private def addrToDesc(addr: Addr): InterfaceOp = {
        val desc = getOrCreate(addr.ifa.index)
        if (describeAddress(addr, desc))
            InterfaceUpdated(desc)
        else
            NoOp
    }

    private def removeAddr(desc: InterfaceDescription, addr: Addr): Unit = {
        addr.ipv4.foreach(ipv4 =>
            desc.getInetAddresses.remove(
                InetAddress.getByAddress(ipv4.toBytes)))
        addr.ipv6.foreach(ipv6 =>
            desc.getInetAddresses.remove(
                InetAddress.getByName(ipv6.toString)))
    }

    private def parseAndEmit(buf: ByteBuffer): Unit =
        interfaceDescriptions.synchronized {
            val nlType = buf.getShort(NetlinkMessage.NLMSG_TYPE_OFFSET)
            buf.position(NetlinkMessage.HEADER_SIZE)
            nlType match {
                case Rtnetlink.Type.NEWLINK =>
                    log.trace("Received NEWLINK notification")
                    maybeEmit(linkToDesc(Link.buildFrom(buf)))
                case Rtnetlink.Type.DELLINK =>
                    log.debug("Received DELLINK notification")
                    val link = Link.buildFrom(buf)
                    val desc = interfaceDescriptions.remove(link.ifi.index)
                    if (desc ne null) {
                        links.remove(link.ifi.index)
                        maybeEmit(InterfaceDeleted(desc))
                    }
                case Rtnetlink.Type.NEWADDR =>
                    log.debug("Received NEWADDR notification")
                    maybeEmit(addrToDesc(Addr.buildFrom(buf)))
                case Rtnetlink.Type.DELADDR =>
                    log.debug("Received DELADDR notification")
                    val addr = Addr.buildFrom(buf)
                    val desc = interfaceDescriptions.get(addr.ifa.index)
                    if (desc ne null) {
                        removeAddr(desc, addr)
                        maybeEmit(InterfaceUpdated(desc))
                    }
                case t: Short =>
                    log.debug(s"Received unexpected notification with type $t")
            }
        }

    private def maybeEmit(op: InterfaceOp) =
        if ((op ne NoOp) && (op.desc.getMac ne null))
            notifications.onNext(op)

    override def subscribe(obs: Observer[InterfaceOp]): Subscription =
        interfaceDescriptions.synchronized {
            interfaceDescriptions.values map InterfaceUpdated foreach obs.onNext
            notifications.subscribe(obs)
        }

    private def builder[T](r: Reader[T], toDesc: T => InterfaceOp) =
        new StateBuilder(r.deserializeFrom _ andThen toDesc) with AwaitableObserver[ByteBuffer]

    private class StateBuilder(toDesc: ByteBuffer => InterfaceOp) extends Observer[ByteBuffer] {
        override def onCompleted(): Unit = { }

        override def onError(e: Throwable): Unit =
            log.error("Failed to build initial interface state", e)

        override def onNext(t: ByteBuffer): Unit =
            maybeEmit(toDesc(t))
    }

    private def init(): Unit = interfaceDescriptions.synchronized {
        log.debug("Retrieving the initial interface information")

        val channel = channelFactory.create(protocol = NetlinkProtocol.NETLINK_ROUTE)
        try {
            val readBuf = BytesUtil.instance.allocateDirect(1024 * 4)
            val broker = new NetlinkRequestBroker(
                new NetlinkBlockingWriter(channel),
                new NetlinkTimeoutReader(channel, 1 minute),
                2,
                1024,
                readBuf,
                NanoClock.DEFAULT,
                headerSize = NetlinkMessage.HEADER_SIZE)

            val linksObserver = builder(Link.deserializer, linkToDesc)
            var seq = broker.nextSequence()
            protocol.prepareLinkList(broker.get(seq))
            broker.publishRequest(seq, linksObserver)

            val addrsObserver = builder(Addr.deserializer, addrToDesc)
            seq = broker.nextSequence()
            protocol.prepareAddrList(broker.get(seq))
            broker.publishRequest(seq, linksObserver)

            broker.writePublishedRequests()

            do {
                broker.readReply()
            } while (!linksObserver.isCompleted || !addrsObserver.isCompleted())

            // Start reading notifications; note that some may already be buffered.
            notificationThread.setDaemon(true)
            notificationThread.start()
            log.debug("Started to scan for interfaces")
        } finally {
            channel.close()
        }
    }

    override def close(): Unit =
        notificationChannel.close()
}
