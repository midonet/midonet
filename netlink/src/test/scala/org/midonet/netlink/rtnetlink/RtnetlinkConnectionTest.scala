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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers, OneInstancePerTest}
import org.slf4j.{Logger, LoggerFactory}
import rx.Observer

import org.midonet.netlink.Netlink.Address
import org.midonet.netlink._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.SystemNanoClock

object RtnetlinkConnectionTest {
    val TestBufferSize = 1024
    val TestIfIndex = 42
    val TestMacString = "01:23:45:67:89:ab"
}

@RunWith(classOf[JUnitRunner])
class RtnetlinkConnectionTest extends FeatureSpec
                              with BeforeAndAfter
                              with Matchers
                              with OneInstancePerTest {
    import org.midonet.netlink.rtnetlink.RtnetlinkConnectionTest._

    val log: Logger = LoggerFactory.getLogger(
        classOf[RtnetlinkConnectionTest])
    // val sendPool = new BufferPool(10, 20, TestBufferSize)
    val sendPool = new MockBufferPool(10, 20, TestBufferSize)
    val anythingObserver = new Observer[ByteBuffer] {
        override def onCompleted(): Unit = {
            log.debug("completed")
        }
        override def onError(e: Throwable): Unit = {
            log.error("An exception was thrown: ", e)
        }
        override def onNext(buf: ByteBuffer): Unit = {
            log.debug("Received data: ", buf)
        }
    }
    private var seq: Int = 1

    val channel = new MockNetlinkChannel(Netlink.selectorProvider,
        NetlinkProtocol.NETLINK_ROUTE) {
        override def read(dst: ByteBuffer) = dst.remaining()
    }

    {
        channel.connect(new Address(0))
    }

    val rtnetlinkConnection =
        new MockRtnetlinkConnection(channel, sendPool, new SystemNanoClock)
    val protocol = new RtnetlinkProtocol(rtnetlinkConnection.pid)

    private def mockSendRequest(prepare: ByteBuffer => Unit): ByteBuffer = {
        val buf = BytesUtil.instance.allocate(TestBufferSize)
        prepare(buf)
        buf.putInt(buf.position() + NetlinkMessage.NLMSG_SEQ_OFFSET, seq)
        seq += 1
        buf
    }

    class CountingObserver[T] extends Observer[T] {
        var onNextCalls = 0
        var onErrorCalls = 0
        var onCompleteCalls = 0

        override def onCompleted(): Unit = onCompleteCalls += 1
        override def onError(e: Throwable): Unit = onErrorCalls += 1
        override def onNext(t: T): Unit = onNextCalls += 1
    }

    private def makeReplyNetlinkHeader(buf: ByteBuffer,
                                       multi: Boolean = false): ByteBuffer = {
        val copied = BytesUtil.instance.allocate(buf.capacity())
        copied.put(buf)
        copied.position(NetlinkMessage.HEADER_SIZE)
        copied.putShort(copied.position + NetlinkMessage.NLMSG_TYPE_OFFSET,
            NLMessageType.DONE)
        if (multi) {
            copied.putShort(copied.position + NetlinkMessage.NLMSG_FLAGS_OFFSET,
                (NLFlag.MULTI | NLFlag.ACK).toShort)
        }
        copied
    }

    private def checkIfSent[T](buf: ByteBuffer,
                               observer: CountingObserver[T]): Unit = {
        val writtenRequest = channel.written.peek()
        writtenRequest.array.length should be (buf.array.length)
        writtenRequest.array should be (buf.array)
        observer.onErrorCalls should be (0)
        // observer.onCompleteCalls should be >= 1
    }

    // private def checkIfReceived[T](buf: ByteBuffer,
    private def checkIfReceived[T](observer: CountingObserver[T]): Unit = {
        // val writtenReply = channel.toRead.peek()
        // writtenReply.array.length should be (buf.array.length)
        // writtenReply.array should be (buf.array)
        observer.onErrorCalls should be (0)
        observer.onNextCalls should be >= 1
        observer.onCompleteCalls should be >= 1
    }

    private
    def newPseudoLink(ifIndex: Int = TestIfIndex,
                      mac: MAC = MAC.fromString(TestMacString),
                      mtu: Short = 1500): Link = {
        val link: Link = new Link()
        link.ifi.ifi_family = Link.Attr.IFLA_UNSPEC
        link.ifi.ifi_pad = 0
        link.ifi.ifi_type = Link.Type.ARPHRD_ETHER
        link.ifi.ifi_index = ifIndex
        link.ifi.ifi_flags = Link.Flag.IFF_UP
        link.mac = mac
        link.mtu = mtu
        link
    }

    private def newPseudoAddr(ifIndex: Int = TestIfIndex): Addr = {
        val addr: Addr = new Addr()
        addr.ifa.ifa_family = Addr.Family.AF_INET
        addr.ifa.ifa_prefixlen = 24
        addr.ifa.ifa_flags = Addr.Flags.IFA_F_SECONDARY
        addr.ifa.ifa_scope = Route.Scope.RT_SCOPE_LINK
        addr.ifa.ifa_index = ifIndex
        addr
    }

    private def newPseudoRoute(dst: IPv4Addr,
                               src: IPv4Addr = IPv4Addr.fromString("0.0.0.0"),
                               prefix: Byte = 24,
                               gw: IPv4Addr = IPv4Addr.fromString("10.0.0.0"),
                               link: Link = newPseudoLink(TestIfIndex)
                               ): Route = {
        val route: Route = new Route()
        route.rtm.rtm_family = Addr.Family.AF_INET
        route.rtm.rtm_dst_len = prefix
        route.rtm.rtm_src_len = 0
        route.rtm.rtm_tos = 0
        route.rtm.rtm_table = Route.Table.RT_TABLE_MAIN
        route.rtm.rtm_protocol = Route.Proto.RTPROT_BOOT
        route.rtm.rtm_scope = 0
        route.rtm.rtm_type = Route.Type.RTN_UNICAST
        route.rtm.rtm_flags = 0
        route.dst = dst
        route.src = src
        route.gw = gw

        route
    }

    private def newPseudoNeigh(ifIndex: Int = TestIfIndex): Neigh = {
        val neigh: Neigh = new Neigh()
        neigh.ndm.ndm_family = Neigh.Attr.NDA_DST
        neigh.ndm.ndm_ifindex = ifIndex
        neigh.ndm.ndm_state = Neigh.State.NUD_INCOMPLETE
        neigh.ndm.ndm_flags = Neigh.Flag.NTF_USE
        neigh.ndm.ndm_type = Neigh.Type.NDA_UNSPEC
        neigh
    }

    before {}

    after {
        channel.written.clear()
        channel.toRead.clear()
        rtnetlinkConnection.replyBuf.clear()
        seq = 0
    }

    feature("Can make requests and get replies through RtnetlinkConnection") {
        scenario("List links request and reply") {
            val link: Link = newPseudoLink(TestIfIndex)
            val observer = new CountingObserver[Set[Link]] {
                override def onNext(notifiedLinks: Set[Link]) = {
                    notifiedLinks.size should be (1)
                    notifiedLinks.head should be (link)
                    super.onNext(notifiedLinks)
                }
            }
            rtnetlinkConnection.linksList(observer)
            val linkListRequest = mockSendRequest(protocol.prepareLinkList)
            checkIfSent(linkListRequest, observer)

            val pseudoLinkRequest = makeReplyNetlinkHeader(linkListRequest)
            Link.describeSetRequest(pseudoLinkRequest, link)
            pseudoLinkRequest.flip()
            val replyBuf = rtnetlinkConnection.replyBuf
            replyBuf.put(pseudoLinkRequest)
            replyBuf.flip()
            replyBuf.putInt(replyBuf.position + NetlinkMessage.NLMSG_LEN_OFFSET,
                replyBuf.limit)
            pseudoLinkRequest.position(NetlinkMessage.HEADER_SIZE)
            rtnetlinkConnection.requestBroker.readReply()
            checkIfReceived(observer)
        }

        scenario("Get a link request and reply") {
            val link: Link = newPseudoLink(TestIfIndex)
            val observer = new CountingObserver[Link] {
                override def onNext(notifiedLink: Link) = {
                    notifiedLink should be (link)
                    super.onNext(notifiedLink)
                }
            }
            rtnetlinkConnection.linksGet(TestIfIndex, observer)
            val linkGetRequest = mockSendRequest(buf =>
                protocol.prepareLinkGet(buf, TestIfIndex))
            checkIfSent(linkGetRequest, observer)

            val pseudoLinkRequest = makeReplyNetlinkHeader(linkGetRequest)
            Link.describeSetRequest(pseudoLinkRequest, link)
            pseudoLinkRequest.flip()
            val replyBuf = rtnetlinkConnection.replyBuf
            replyBuf.put(pseudoLinkRequest)
            replyBuf.flip()
            replyBuf.putInt(replyBuf.position + NetlinkMessage.NLMSG_LEN_OFFSET,
                replyBuf.limit)
            pseudoLinkRequest.position(NetlinkMessage.HEADER_SIZE)
            rtnetlinkConnection.requestBroker.readReply()
            checkIfReceived(observer)
        }

        scenario("Create a link request and reply") {
            val link: Link = newPseudoLink(TestIfIndex)
            val observer = new CountingObserver[Link] {
                override def onNext(notifiedLink: Link) = {
                    notifiedLink should be (link)
                    super.onNext(notifiedLink)
                }
            }
            rtnetlinkConnection.linksCreate(link, observer)
            val linkCreateRequest = mockSendRequest(buf =>
                protocol.prepareLinkCreate(buf, link))
            checkIfSent(linkCreateRequest, observer)

            val pseudoLinkRequest = makeReplyNetlinkHeader(linkCreateRequest)
            Link.describeSetRequest(pseudoLinkRequest, link)
            pseudoLinkRequest.flip()
            val replyBuf = rtnetlinkConnection.replyBuf
            replyBuf.put(pseudoLinkRequest)
            replyBuf.flip()
            replyBuf.putInt(replyBuf.position + NetlinkMessage.NLMSG_LEN_OFFSET,
                replyBuf.limit)
            pseudoLinkRequest.position(NetlinkMessage.HEADER_SIZE)
            rtnetlinkConnection.requestBroker.readReply()
            checkIfReceived(observer)
        }

        scenario("Set link addr request and reply") {
            val link: Link = newPseudoLink(TestIfIndex)
            val mac: MAC = MAC.fromString(TestMacString)
            val observer = new CountingObserver[Boolean] {
                override def onNext(notifiedBool: Boolean): Unit = {
                   notifiedBool should be (true)
                    super.onNext(notifiedBool)
                }
            }
            rtnetlinkConnection.linksSetAddr(link, mac, observer)
            val setLinkAddrRequest = mockSendRequest(buf =>
                protocol.prepareLinkSetAddr(buf, link, mac))
            checkIfSent(setLinkAddrRequest, observer)
            val pseudoLinkRequest = makeReplyNetlinkHeader(setLinkAddrRequest)
            link.mac = mac
            Link.describeSetRequest(pseudoLinkRequest, link)
            pseudoLinkRequest.flip()
            val replyBuf = rtnetlinkConnection.replyBuf
            replyBuf.put(pseudoLinkRequest)
            replyBuf.flip()
            replyBuf.putInt(replyBuf.position + NetlinkMessage.NLMSG_LEN_OFFSET,
                replyBuf.limit)
            pseudoLinkRequest.position(NetlinkMessage.HEADER_SIZE)
            rtnetlinkConnection.requestBroker.readReply()
            checkIfReceived(observer)
        }

        scenario("Set link request and reply") {
            val link: Link = newPseudoLink(TestIfIndex)
            val mac: MAC = MAC.fromString(TestMacString)
            val observer = new CountingObserver[Boolean] {
                override def onNext(notifiedBool: Boolean): Unit = {
                    notifiedBool should be (true)
                    super.onNext(notifiedBool)
                }
            }
            rtnetlinkConnection.linksSet(link, observer)
            val setLinkAddrRequest = mockSendRequest(buf =>
                protocol.prepareLinkSet(buf, link))
            checkIfSent(setLinkAddrRequest, observer)

            val pseudoLinkRequest = makeReplyNetlinkHeader(setLinkAddrRequest)
            link.mac = mac
            Link.describeSetRequest(pseudoLinkRequest, link)
            pseudoLinkRequest.flip()
            val replyBuf = rtnetlinkConnection.replyBuf
            replyBuf.put(pseudoLinkRequest)
            replyBuf.flip()
            replyBuf.putInt(replyBuf.position + NetlinkMessage.NLMSG_LEN_OFFSET,
                replyBuf.limit)
            pseudoLinkRequest.position(NetlinkMessage.HEADER_SIZE)
            rtnetlinkConnection.requestBroker.readReply()
            checkIfReceived(observer)
        }

        scenario("List addrs request and reply") {
            val addr: Addr = newPseudoAddr(TestIfIndex)
            val observer = new CountingObserver[Set[Addr]] {
                override def onNext(notifiedAddrs: Set[Addr]) = {
                    notifiedAddrs.size should be (1)
                    notifiedAddrs.head should be (addr)
                    super.onNext(notifiedAddrs)
                }
            }
            rtnetlinkConnection.addrsList(observer)
            val addrListRequest =
                mockSendRequest(buf => protocol.prepareAddrList(buf))
            checkIfSent(addrListRequest, observer)

            val pseudoAddrRequest = makeReplyNetlinkHeader(addrListRequest)
            Addr.describeNewRequest(pseudoAddrRequest, addr)
            pseudoAddrRequest.flip()
            rtnetlinkConnection.replyBuf.put(pseudoAddrRequest)
            rtnetlinkConnection.replyBuf.flip()
            pseudoAddrRequest.position(NetlinkMessage.HEADER_SIZE)
            rtnetlinkConnection.requestBroker.readReply()
            checkIfReceived(observer)
        }

/*        scenario("Get an addr reply") {
            val addr: Addr = newPseudoAddr(TestIfIndex)
            val observer = new CountingObserver[Addr] {
                override def onNext(notifiedAddr: Addr) = {
                    notifiedAddr should be (addr)
                    super.onNext(notifiedAddr)
                }
            }
            rtnetlinkConnection.addrsGet(TestIfIndex, observer)
            val addrGetRequest = mockSendRequest(buf =>
                protocol.prepareAddrGet(buf, TestIfIndex))
            checkIfSent(addrGetRequest, observer)

            val pseudoAddrRequest = makeReplyNetlinkHeader(addrGetRequest)
            Addr.describeNewRequest(pseudoAddrRequest, addr)
            pseudoAddrRequest.flip()
            rtnetlinkConnection.replyBuf.put(pseudoAddrRequest)
            rtnetlinkConnection.replyBuf.flip()
            pseudoAddrRequest.position(NetlinkMessage.HEADER_SIZE)
            rtnetlinkConnection.requestBroker.readReply()
            checkIfReceived(observer)
        }*/

        scenario("List a route reply") {
            val link: Link = newPseudoLink(TestIfIndex)
            val route: Route =
                newPseudoRoute(IPv4Addr.fromString("192.168.1.1"))
            val observer = new CountingObserver[Set[Route]] {
                override def onNext(notifiedRoutes: Set[Route]) = {
                    notifiedRoutes.size should be (1)
                    notifiedRoutes.head should be (route)
                    super.onNext(notifiedRoutes)
                }
            }
            rtnetlinkConnection.routesList(observer)
            val routeListRequest = mockSendRequest(protocol.prepareRouteList)
            checkIfSent(routeListRequest, observer)

            val pseudoRouteRequest = makeReplyNetlinkHeader(routeListRequest)
            Route.describeSetRequest(pseudoRouteRequest, route, link)
            pseudoRouteRequest.flip()
            val replyBuf = rtnetlinkConnection.replyBuf
            replyBuf.put(pseudoRouteRequest)
            replyBuf.flip()
            replyBuf.putInt(replyBuf.position + NetlinkMessage.NLMSG_LEN_OFFSET,
                replyBuf.limit)
            pseudoRouteRequest.position(NetlinkMessage.HEADER_SIZE)
            rtnetlinkConnection.requestBroker.readReply()
            checkIfReceived(observer)
        }

        scenario("Get a route request and reply") {
            val dst = IPv4Addr.fromString("192.168.1.1")
            val link: Link = newPseudoLink(TestIfIndex)
            val route: Route = newPseudoRoute(dst)
            val observer = new CountingObserver[Route] {
                override def onNext(notifiedRoute: Route) = {
                    notifiedRoute should be (route)
                    super.onNext(notifiedRoute)
                }
            }
            rtnetlinkConnection.routesGet(dst, observer)
            val routeGetRequest = mockSendRequest(buf =>
                protocol.prepareRouteGet(buf, dst))
            checkIfSent(routeGetRequest, observer)

            val pseudoRouteRequest = makeReplyNetlinkHeader(routeGetRequest)
            Route.describeSetRequest(pseudoRouteRequest, route, link)
            pseudoRouteRequest.flip()
            val replyBuf = rtnetlinkConnection.replyBuf
            replyBuf.put(pseudoRouteRequest)
            replyBuf.flip()
            replyBuf.putInt(replyBuf.position + NetlinkMessage.NLMSG_LEN_OFFSET,
                replyBuf.limit)
            pseudoRouteRequest.position(NetlinkMessage.HEADER_SIZE)
            rtnetlinkConnection.requestBroker.readReply()
            checkIfReceived(observer)
        }

        scenario("List neight request and reply") {
            val neigh: Neigh = newPseudoNeigh(TestIfIndex)
            val observer = new CountingObserver[Set[Neigh]] {
                override def onNext(notifiedNeigh: Set[Neigh]) = {
                    notifiedNeigh.size should be (1)
                    notifiedNeigh.head should be (neigh)
                    super.onNext(notifiedNeigh)
                }
            }
            rtnetlinkConnection.neighsList(observer)
            val neighListRequest =
                mockSendRequest(buf => protocol.prepareNeighList(buf))
            checkIfSent(neighListRequest, observer)

            val pseudoAddrRequest = makeReplyNetlinkHeader(neighListRequest)
            Neigh.describeNewRequest(pseudoAddrRequest, neigh)
            pseudoAddrRequest.flip()
            rtnetlinkConnection.replyBuf.put(pseudoAddrRequest)
            rtnetlinkConnection.replyBuf.flip()
            pseudoAddrRequest.position(NetlinkMessage.HEADER_SIZE)
            rtnetlinkConnection.requestBroker.readReply()
            checkIfReceived(observer)
        }
    }
}
