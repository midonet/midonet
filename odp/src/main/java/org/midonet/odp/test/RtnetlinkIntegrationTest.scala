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

package org.midonet.odp.test

import java.nio.ByteBuffer

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.sys.process._

import org.slf4j.{Logger, LoggerFactory}
import rx.Observer

import org.midonet.netlink._
import org.midonet.netlink.Netlink.Address
import org.midonet.netlink.NetlinkConnection.DefaultNetlinkGroup
import org.midonet.netlink.rtnetlink._
import org.midonet.odp.util.TapWrapper
import org.midonet.packets.{MAC, IPv4Addr}
import org.midonet.util.IntegrationTests._
import org.midonet.util.concurrent.{NanoClock, SystemNanoClock}

object MockSelectorBasedRtnetlinkConnection {
    val log: Logger = LoggerFactory.getLogger(classOf[RtnetlinkConnection])

    def apply(addr: Netlink.Address, sendPool: BufferPool,
              groups: Int = DefaultNetlinkGroup) = try {
        val channel = Netlink.selectorProvider.openNetlinkSocketChannel(
            NetlinkProtocol.NETLINK_ROUTE, groups)

        if (channel == null) {
            log.error("Error creating a NetlinkChannel. Presumably, " +
                "java.library.path is not set.")
        } else {
            channel.connect(addr)
        }
        val conn = new MockSelectorBasedRtnetlinkConnection(
            channel, sendPool, new SystemNanoClock)
        conn.start()
        conn
    } catch {
        case ex: Exception =>
            log.error("Error connectin to rtnetlink.")
            throw new RuntimeException(ex)
    }
}

class MockSelectorBasedRtnetlinkConnection(channel: NetlinkChannel,
                                           sendPool: BufferPool,
                                           clock: NanoClock)
        extends SelectorBasedRtnetlinkConnection(channel, sendPool, clock) {
    import MockSelectorBasedRtnetlinkConnection._
    import RtnetlinkTest._

    var testNotificationObserver: NotificationTestObserver = null

    override def readMessage(observer: Observer[ByteBuffer] =
                             NoopNotificationTestObserver): Unit = {
        val obs = if (testNotificationObserver != null) {
            testNotificationObserver
        } else {
            observer
        }
        super.readMessage(obs)
    }
}

object RtnetlinkTest {
    val OK = "ok"
    val TestIpAddr = "192.168.42.1"
    val TestNeighbourIpAddr = "192.168.42.42"
    val TestNeighbourMacAddr = MAC.random()

    val log: Logger =
        LoggerFactory.getLogger(classOf[RtnetlinkIntegrationTestBase])

    private[test]
    object TestObserver {
        def apply[T](condition: T => Boolean)
                    (implicit promise: Promise[String] = Promise[String]()) =
            new TestObserver[T] {
                override def check(resource: T): Boolean =
                    condition(resource)
            }
    }

    private[test]
    abstract class TestObserver[T](implicit promise: Promise[String])
            extends Observer[T] {
        def check(resource: T): Boolean

        def test: Future[String] = promise.future
        override def onCompleted(): Unit = { promise.trySuccess(OK) }
        override def onError(t: Throwable): Unit = { promise.tryFailure(t) }
        override def onNext(resource: T): Unit = try {
            if (!check(resource)) {
                promise.tryFailure(UnexpectedResultException)
            }
        } catch {
            case t: Throwable => promise.failure(t)
        }
    }

    private[test]
    object NotificationTestObserver {
        def apply(condition: ByteBuffer => Boolean)
                 (implicit promise: Promise[String] = Promise[String]()) =
        new NotificationTestObserver {
            override def check(buf: ByteBuffer): Boolean = condition(buf)
        }
    }

    private[test]
    abstract class NotificationTestObserver(implicit promise: Promise[String])
            extends TestObserver[ByteBuffer] {
        var notifiedLinks: mutable.ListBuffer[Link] = mutable.ListBuffer()
        var notifiedAddrs: mutable.ListBuffer[Addr] = mutable.ListBuffer()
        var notifiedRoutes: mutable.ListBuffer[Route] = mutable.ListBuffer()
        var notifiedNeighs: mutable.ListBuffer[Neigh] = mutable.ListBuffer()

        def clear(): Unit = {
            notifiedLinks.clear()
            notifiedAddrs.clear()
            notifiedRoutes.clear()
            notifiedNeighs.clear()
        }

        def check(buf: ByteBuffer): Boolean

        override def onCompleted(): Unit = { promise.trySuccess(OK) }
        override def onError(e: Throwable): Unit = { promise.tryFailure(e) }
        override def onNext(buf: ByteBuffer): Unit = {
            val NetlinkHeader(_, nlType, _, seq, _) =
                NetlinkConnection.readNetlinkHeader(buf)
            if (seq != 0) {
                return
            }
            // Add/update or remove a new entry to/from local data of
            // InterfaceScanner.
            //   http://www.infradead.org/~tgr/libnl/doc/route.html
            nlType match {
                case Rtnetlink.Type.NEWLINK =>
                    val link = Link.buildFrom(buf)
                    notifiedLinks += link
                case Rtnetlink.Type.DELLINK =>
                    val link = Link.buildFrom(buf)
                    notifiedLinks -= link
                case Rtnetlink.Type.NEWADDR =>
                    val addr = Addr.buildFrom(buf)
                    notifiedAddrs += addr
                case Rtnetlink.Type.DELADDR =>
                    val addr = Addr.buildFrom(buf)
                    notifiedAddrs -= addr
                case Rtnetlink.Type.NEWROUTE =>
                    val route = Route.buildFrom(buf)
                    notifiedRoutes += route
                case Rtnetlink.Type.DELROUTE =>
                    val route = Route.buildFrom(buf)
                    notifiedRoutes -= route
                case Rtnetlink.Type.NEWNEIGH =>
                    val neigh = Neigh.buildFrom(buf)
                    notifiedNeighs += neigh
                case Rtnetlink.Type.DELNEIGH =>
                    val neigh = Neigh.buildFrom(buf)
                    notifiedNeighs -= neigh
                case _ => // Ignore other notifications.
            }
            if (!check(buf)) {
                promise.tryFailure(UnexpectedResultException)
            }
        }
    }

    val NoopNotificationTestObserver: NotificationTestObserver = {
        implicit val promise = Promise[String]()
        new  NotificationTestObserver {
            override def check(buf: ByteBuffer): Boolean = true
        }
    }
}

trait RtnetlinkTest {
    import org.midonet.odp.test.RtnetlinkTest._

    val conn: MockSelectorBasedRtnetlinkConnection
    val tapName = "rtnetlink_test"  // Tap name length should be less than 15.
    var tapId: Int = 0
    var tap: TapWrapper = null

    def start(): Unit = {
        tap = new TapWrapper(tapName, true)
        tap.up()
        tapId = (s"ip link show $tapName" #|
            "head -n 1" #|
            "cut -b 1,2,3" !!).replace(":", "").trim.toInt
    }

    def stop(): Unit = {
        tap.down()
        tap.remove()
    }

    def listLinkNumberTest: Test = {
        val desc = """the number of listed links should equal to the result of
                     |`ip link list`
                   """.stripMargin.replaceAll("\n", " ")
        val obs = TestObserver { links: Set[Link] =>
            val ipLinkNum = ("ip link list" #| "wc -l" !!).trim.toInt / 2
            links.size == ipLinkNum
        }

        conn.linksList(obs)

        (desc, obs.test)
    }
    val ListlinkNumberTest: LazyTest = () => listLinkNumberTest

    def getLinkTest: Test = {
        val desc = """the interface id should be identical to the result of `ip
                     |link show`.
                   """.stripMargin.replaceAll("\n", " ")
        val obs = TestObserver { link: Link =>
            link.ifi.ifi_index == tapId
        }

        conn.linksGet(tapId, obs)

        (desc, obs.test)
    }
    val GetLinkTest: LazyTest = () => getLinkTest

    def createLinkTest: Test = {
        val desc = "the created interface should equal to the original one."

        val link = new Link
        link.ifi.ifi_family = 0
        link.ifi.ifi_pad = 0
        link.ifi.ifi_type = 0
        link.ifi.ifi_index = 0
        link.ifi.ifi_flags = 0x0
        link.ifi.ifi_change = 0x0
        link.ifname = s"${tapName}_"

        val obs = TestObserver { createdLink: Link =>
            createdLink == link
        }

        conn.linksCreate(link, obs)

        obs.test.recover { case _ => s"ip link del ${tapName}_".! }

        (desc, obs.test)
    }
    val CreateLinkTest: LazyTest = () => createLinkTest

    def newLinkNotificationTest: Test = {
        val desc = """the link created by `ip link add` should be notified to
                     |the notification observer.
                   """.stripMargin.replaceAll("\n", " ")
        implicit val promise = Promise[String]()

        var linkNum = 0
        conn.testNotificationObserver = new NotificationTestObserver {
            def check(buf: ByteBuffer): Boolean = {
                this.notifiedLinks.size == (linkNum + 1)
            }
        }

        conn.testNotificationObserver.clear()
        linkNum = conn.testNotificationObserver.notifiedLinks.size

        if (s"ip tuntap add dev ${tapName}2 mode tap".! != 0) {
            promise.failure(TestPrepareException)
        }

        promise.future.andThen { case _ =>
            conn.testNotificationObserver.clear()
            s"ip link del ${tapName}2".!
        }

        (desc, promise.future)
    }
    val NewLinkNotificationTest: LazyTest  = () => newLinkNotificationTest

    def listAddrTest: Test = {
        val desc = """the number of addresses should be identical to the result
                     |of `ip addr list`.
                   """.stripMargin.replaceAll("\n", " ")
        val obs = TestObserver { addrs: Set[Addr] =>
            val ipAddrsNum = ("ip address list" #| "grep -v inet6" #|
                "grep inet" #| "wc -l" !!).trim.toInt
            addrs.size == ipAddrsNum
        }

        conn.addrsList(obs)

        (desc, obs.test)
    }
    val ListAddrTest: LazyTest = () => listAddrTest

    def getAddrTest: Test = {
        val desc = """the address of the interface should be identical to what
                     |is created by `ip addr add`.
                   """.stripMargin.replaceAll("\n", " ")
        val promise = Promise[String]()
        if (s"ip address add $TestIpAddr dev $tapName".! != 0) {
            promise.failure(TestPrepareException)
        }

        val obs = TestObserver { addrs: Set[Addr] =>
            val ipAddrsNum = (s"ip addr show dev $tapName" #| "grep -v inet6" #|
                "grep inet" #| "wc -l" !!).trim.toInt
            val filteredAddrs = addrs.filter(_.ifa.ifa_index == tapId)
            filteredAddrs.size == ipAddrsNum && filteredAddrs.forall {
                addr: Addr =>
                    addr.ifa.ifa_index == tapId &&
                        addr.ifa.ifa_prefixlen == 32 &&
                        addr.ipv4.size() == 1 &&
                        addr.ipv4.head == IPv4Addr.fromString(TestIpAddr)
            }
        }(promise)

        // conn.addrsGet(tapId, obs)
        conn.addrsList(obs)

        obs.test.andThen { case _ => s"ip address flush dev $tapName".! }

        (desc, obs.test)
    }
    val GetAddrTest: LazyTest = () => getAddrTest

    def newAddrNotificationTest: Test = {
        val desc = """the address created by `ip address add` should be notified
                     |to the notification obsever .
                   """.stripMargin.replaceAll("\n", " ")
        implicit val promise = Promise[String]()
        var addrNum = 0
        conn.testNotificationObserver = new NotificationTestObserver {
            override def check(buf: ByteBuffer): Boolean = {
                this.notifiedAddrs.size == (addrNum + 1)
            }
        }
        conn.testNotificationObserver.clear()
        addrNum = conn.testNotificationObserver.notifiedAddrs.size
        if (s"ip address add $TestIpAddr dev $tapName".! != 0) {
            promise.failure(TestPrepareException)
        }

        promise.future.andThen { case _ =>
            s"ip address flush dev $tapName".!
        }

        (desc, promise.future)
    }
    val NewAddrNotificationTest: LazyTest = () => newAddrNotificationTest

    def listRouteTest: Test = {
        val desc = """the number of IPv4 entries of the default routing table
                     |should be identical to the result of `ip route list`.
                   """.stripMargin.replaceAll("\n", " ")
        val obs = TestObserver { routes: Set[Route] =>
            val routesNum = ("ip route list" #| "wc -l" !!).trim.toInt
            val filteredRoutes = routes.filter(r =>
                r.rtm.rtm_table == 254.toByte &&
                r.rtm.rtm_family == Addr.Family.AF_INET)
            filteredRoutes.size == routesNum
        }

        conn.routesList(obs)

        (desc, obs.test)
    }
    val ListRouteTest: LazyTest = () => listRouteTest

    def newRouteNotificationTest: Test = {
        val desc = """the route created by `ip route add` should be notified to
                     |the notification observer.
                   """.stripMargin.replaceAll("\n", " ")
        implicit val promise = Promise[String]()
        val dst = "192.168.42.0"
        val dstSubnet = s"$dst/24"
        var routeNum = 0

        conn.testNotificationObserver = new NotificationTestObserver {
            def check(buf: ByteBuffer): Boolean = {
                this.notifiedRoutes.size == (routeNum + 1)
            }
        }

        conn.testNotificationObserver.clear()
        routeNum = conn.testNotificationObserver.notifiedRoutes.size

        if ((s"ip address add $TestIpAddr dev $tapName".! != 0) &&
            (s"ip route add $dstSubnet via $TestIpAddr dev $tapName".! != 0)) {
            promise.failure(TestPrepareException)
        }

        promise.future.andThen { case _ =>
            s"ip route flush dev $tapName".!
            s"ip address flush dev $tapName".!
        }

        (desc, promise.future)
    }
    val NewRouteNotificationTest: LazyTest = () => newRouteNotificationTest

    def getRouteTest: Test = {
        val desc = """the address of the interface should be identical to what
                     |created by `ip route add`.
                   """.stripMargin.replaceAll("\n", " ")
        val promise = Promise[String]()
        val dst = "192.168.42.0"
        val dstSubnet = s"$dst/24"
        if ((s"ip address add $TestIpAddr dev $tapName".! != 0) &&
            (s"ip route add $dstSubnet via $TestIpAddr dev $tapName".! != 0)) {
            promise.failure(TestPrepareException)
        }
        val obs = TestObserver { route: Route =>
            route.rtm.rtm_dst_len == 32 &&
                route.rtm.rtm_family == Route.Family.AF_INET &&
                route.dst == IPv4Addr.fromString(dst)
        }(promise)

        conn.routesGet(IPv4Addr.fromString(dst), obs)

        obs.test.andThen { case _ =>
            s"ip route flush dev $tapName".!
            s"ip address flush dev $tapName".!
        }

        (desc, obs.test)
    }
    val GetRouteTest = () => getRouteTest

    def listNeighTest: Test = {
        val desc = """the number of neighbours should be identical to the result
                     |of `ip neigh list`.
                   """.stripMargin.replaceAll("\n", " ")
        val obs = TestObserver { neighs: Set[Neigh] =>
            val ipNeighsNum = ("ip neigh list" #| "grep REACHABLE" #|
                "wc -l" !!).trim.toInt
            val filteredNeighs = neighs.filter(
                _.ndm.ndm_state == Neigh.State.NUD_REACHABLE)
            filteredNeighs.size == ipNeighsNum
        }

        conn.neighsList(obs)

        (desc, obs.test)
    }
    val ListNeighTest = () => listNeighTest

    def newNeighNotificationTest: Test = {
        val desc = """the neighbour created by `ip neighbour add` should be
                     |notified to the notification observer
                   """.stripMargin.replaceAll("\n", " ")
        implicit val promise = Promise[String]()
        var neighNum = 0

        conn.testNotificationObserver = new NotificationTestObserver {
            override def check(buf: ByteBuffer): Boolean =
                this.notifiedNeighs.size == (neighNum + 1)
        }

        conn.testNotificationObserver.clear()
        neighNum = conn.testNotificationObserver.notifiedNeighs.size

        if ((s"ip neighbour add $TestNeighbourIpAddr lladdr " +
            s"$TestNeighbourMacAddr dev $tapName nud permanent").! != 0) {
            promise.failure(TestPrepareException)
        }

        promise.future.andThen { case _ =>
            s"ip neighbour flush dev $tapName".!
        }

        (desc, promise.future)
    }
    val NewNeighNotificationTest: LazyTest = () => newNeighNotificationTest

    private def sequentialTests(tests: Test*): TestSuite = {
        val currentTests: List[Test] = tests.toList
        val formerTests: List[Test] =
            ("", Future.successful(OK)) +: currentTests
        val results: List[Test] = formerTests.zip(currentTests).map {
            case ((_, formerTest), (desc, currentTest)) =>
                (desc, formerTest.flatMap(_ => currentTest))
        }
        results.toSeq
    }

    val LinkTests: LazyTestSuite = Seq(ListlinkNumberTest, GetLinkTest,
        NewLinkNotificationTest)
    val AddrTests: LazyTestSuite = Seq(ListAddrTest, GetAddrTest,
        NewAddrNotificationTest)
    val RouteTests: LazyTestSuite = Seq(GetRouteTest, ListRouteTest,
        NewRouteNotificationTest)
    val NeighTests: LazyTestSuite = Seq(ListNeighTest, NewNeighNotificationTest)
}

class RtnetlinkIntegrationTestBase extends RtnetlinkTest {
    val sendPool = new BufferPool(10, 20, 1024)
    override val conn: MockSelectorBasedRtnetlinkConnection =
        MockSelectorBasedRtnetlinkConnection(new Address(0), sendPool)

    def run(): Boolean = {
        var passed = true
        try {
            start()
            passed &= printReport(runLazySuite(LinkTests))
            passed &= printReport(runLazySuite(AddrTests))
            passed &= printReport(runLazySuite(RouteTests))
            passed &= printReport(runLazySuite(NeighTests))
        } finally {
            stop()
            conn.stop()
        }
        passed
    }

    def main(args: Array[String]): Unit =
        System.exit(if (run()) 0 else 1)
}

object RtnetlinkIntegrationTest extends RtnetlinkIntegrationTestBase
