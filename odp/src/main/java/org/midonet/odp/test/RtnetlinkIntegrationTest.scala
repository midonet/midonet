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

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.sys.process._

import org.slf4j.{Logger, LoggerFactory}
import rx.{Observable, Observer}
import rx.subjects.PublishSubject

import org.midonet.netlink._
import org.midonet.netlink.rtnetlink._
import org.midonet.odp.util.TapWrapper
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.IntegrationTests._
import org.midonet.util.concurrent.NanoClock
import org.midonet.util.functors._


object TestableSelectorBasedRtnetlinkConnection extends
        RtnetlinkConnectionFactory[TestableSelectorBasedRtnetlinkConnection] {
    override  def apply() = {
        val conn = super.apply()
        conn.start()
        conn
    }
}

class TestableSelectorBasedRtnetlinkConnection(channel: NetlinkChannel,
                                               maxPendingRequests: Int,
                                               maxRequestSize: Int,
                                               clock: NanoClock)
        extends SelectorBasedRtnetlinkConnection(channel, maxPendingRequests,
            maxRequestSize, clock)
        with NetlinkNotificationReader {
    import RtnetlinkTest._

    val testNotificationObserver: NotificationTestObserver =
        TestableNotificationObserver
    override lazy val notificationChannel =
        (new NetlinkChannelFactory).create(false, NetlinkProtocol.NETLINK_ROUTE)

    @throws[IOException]
    @throws[InterruptedException]
    @throws[ExecutionException]
    override def start(): Unit = try {
        super.start()
        startReadThread(notificationChannel, s"$name-notification") {
            readNotifications(testNotificationObserver)
        }
    } catch {
        case ex: IOException => try {
            super.stop()
            stopReadThread(notificationChannel)
        } catch {
            case _: Exception => throw ex
        }
    }

    override def stop(): Unit = {
        logger.info(s"Stopping rtnetlink notification channel: $name")
        super.stop()
        stopReadThread(notificationChannel)
    }
}

object RtnetlinkTest {
    val OK = "ok"
    val TestIpAddr = "192.168.42.1"
    val TestAnotherIpAddr = "192.168.42.10"
    val TestNeighbourIpAddr = "192.168.42.42"
    val TestNeighbourMacAddr = MAC.random()

    val log: Logger =
        LoggerFactory.getLogger(classOf[RtnetlinkIntegrationTestBase])

    private[test]
    object TestObserver {
        def apply[T](condition: T => Boolean)
                    (implicit promise: Promise[String] = Promise[String]()) =
            new TestObserver[T] {
                override var check = condition
            }
    }

    private[test]
    abstract class TestObserver[T](implicit promise: Promise[String])
            extends Observer[T] {
        var check: T => Boolean

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

    implicit private[this]
    def closureToTestObserver[T](closure: T => Boolean)
                                (implicit p: Promise[String]): TestObserver[T] =
        TestObserver.apply(closure)

    private[test]
    object NotificationTestObserver {
        def apply(condition: ByteBuffer => Boolean)
                 (implicit promise: Promise[String] = Promise[String]()) = {
            val obs = new NotificationTestObserver
            obs.check = condition
            obs
        }
    }

    private[test]
    class NotificationTestObserver(implicit var promise: Promise[String])
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

        override var check: ByteBuffer => Boolean = (buf: ByteBuffer) => true
        val defaultNotificationHandler: (Short, ByteBuffer) => Unit = {
            (nlType, buf) => {
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
                } else {
                    promise.trySuccess(OK)
                }
            }
        }
        var handleNotification = defaultNotificationHandler

        override def onCompleted(): Unit = { }
        override def onError(e: Throwable): Unit = { promise.tryFailure(e) }
        override def onNext(buf: ByteBuffer): Unit = {
            val NetlinkHeader(_, nlType, _, seq, _) =
                NetlinkUtil.readNetlinkHeader(buf)
            if (seq != 0 && (nlType != Rtnetlink.Type.NEWADDR &&
                nlType != Rtnetlink.Type.DELADDR)) {
                return
            }
            handleNotification(nlType, buf)
        }
    }

    val TestableNotificationObserver: NotificationTestObserver = {
        implicit val promise = Promise[String]()
        new  NotificationTestObserver
    }
}

trait RtnetlinkTest {
    import org.midonet.odp.test.RtnetlinkTest._

    val conn: TestableSelectorBasedRtnetlinkConnection
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
            link.ifi.index == tapId
        }

        conn.linksGet(tapId, obs)

        (desc, obs.test)
    }
    val GetLinkTest: LazyTest = () => getLinkTest

    def createLinkTest: Test = {
        val desc = "the created interface should equal to the original one."

        val link = new Link
        link.ifi.family = 0
        link.ifi.`type` = 0
        link.ifi.index = 0
        link.ifi.flags = 0x0
        link.ifi.change = 0x0
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

        conn.synchronized {
            val notificationObserver = conn.testNotificationObserver
            notificationObserver.promise = promise
            notificationObserver.handleNotification = {
                (nlType, buf) =>
                    nlType match {
                        case Rtnetlink.Type.NEWLINK =>
                            val link = Link.buildFrom(buf)
                            notificationObserver.notifiedLinks += link
                            if (notificationObserver.notifiedLinks.size
                                != (linkNum + 1)) {
                                promise.tryFailure(UnexpectedResultException)
                            } else {
                                promise.trySuccess(OK)
                            }
                        case _ =>
                    }
            }
            notificationObserver.notifiedLinks.clear()
            linkNum = notificationObserver.notifiedLinks.size

            if (s"ip tuntap add dev ${tapName}2 mode tap".! != 0) {
                promise.failure(TestPrepareException)
            }
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
            val ipAddrsNum = ("ip address list" #|
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
            val ipAddrsNum = (s"ip addr show dev $tapName" #|
                "grep inet" #| "wc -l" !!).trim.toInt
            val filteredAddrs = addrs.filter(_.ifa.index == tapId)
            filteredAddrs.size == ipAddrsNum && filteredAddrs.exists {
                addr: Addr =>
                    addr.ifa.index == tapId &&
                        addr.ifa.prefixLen == 32 &&
                        addr.ipv4.size() == 1 &&
                        addr.ipv4.get(0) == IPv4Addr.fromString(TestIpAddr)
            }
        }(promise)

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

        conn.synchronized {
            val notificationObserver = conn.testNotificationObserver
            notificationObserver.promise = promise
            notificationObserver.handleNotification = {
                (nlType, buf) =>
                    nlType match {
                        case Rtnetlink.Type.NEWADDR =>
                            val addr = Addr.buildFrom(buf)
                            notificationObserver.notifiedAddrs += addr
                            if (notificationObserver.notifiedAddrs.size
                                != (addrNum + 1)) {
                                promise.tryFailure(UnexpectedResultException)
                            } else {
                                promise.trySuccess(OK)
                            }
                        case _ =>
                    }
            }
            notificationObserver.notifiedAddrs.clear()
            addrNum = notificationObserver.notifiedAddrs.size

            if (s"ip address add $TestAnotherIpAddr dev $tapName".! != 0) {
                promise.failure(TestPrepareException)
            }
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
                r.rtm.table == 254.toByte &&
                r.rtm.family == Addr.Family.AF_INET)
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

        conn.synchronized {
            val notificationObserver = conn.testNotificationObserver
            notificationObserver.promise = promise
            notificationObserver.handleNotification = {
                (nlType, buf) =>
                    nlType match {
                        case Rtnetlink.Type.NEWROUTE =>
                            val route = Route.buildFrom(buf)
                            notificationObserver.notifiedRoutes += route
                            if (notificationObserver.notifiedRoutes.size !=
                                (routeNum + 1)) {
                                promise.tryFailure(UnexpectedResultException)
                            } else {
                                promise.trySuccess(OK)
                            }
                        case _ =>
                    }
            }
            // conn.testNotificationObserver.check = (buf: ByteBuffer) =>
            // conn.testNotificationObserver.notifiedRoutes.size == (routeNum + 1)
            notificationObserver.notifiedRoutes.clear()
            routeNum = notificationObserver.notifiedRoutes.size

            if ((s"ip address add $TestIpAddr dev $tapName".! != 0) &&
                (s"ip route add $dstSubnet via $TestIpAddr dev $tapName".! != 0)) {
                promise.failure(TestPrepareException)
            }
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
            route.rtm.dstLen == 32 &&
                route.rtm.family == Route.Family.AF_INET &&
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
                _.ndm.state == Neigh.State.NUD_REACHABLE)
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

        this.synchronized {
            val notificationObserver = conn.testNotificationObserver
            notificationObserver.promise = promise
            notificationObserver.handleNotification = {
                (nlType, buf) =>
                    nlType match {
                        case Rtnetlink.Type.NEWNEIGH =>
                            val neigh = Neigh.buildFrom(buf)
                            notificationObserver.notifiedNeighs += neigh
                            if (notificationObserver.notifiedRoutes.size !=
                                (neighNum + 1)) {
                                promise.tryFailure(UnexpectedResultException)
                            } else {
                                promise.trySuccess(OK)
                            }
                        case _ =>
                    }
            }
            notificationObserver.notifiedNeighs.clear()
            neighNum = notificationObserver.notifiedNeighs.size

            if ((s"ip neighbour add $TestNeighbourIpAddr lladdr " +
                s"$TestNeighbourMacAddr dev $tapName nud permanent").! != 0) {
                promise.failure(TestPrepareException)
            }
        }

        promise.future.andThen { case _ =>
            s"ip neighbour flush dev $tapName".!
        }

        (desc, promise.future)
    }
    val NewNeighNotificationTest: LazyTest = () => newNeighNotificationTest

    def listLinksAndAddrs: Test = {
        val desc ="""listing addresses following right after listing links
                    |should not experience [16] Resource or device busy.
                  """.stripMargin.replaceAll("\n", " ")
        implicit val promise = Promise[String]()

        val linksSubject = PublishSubject.create[Set[Link]]
        val addrsSubject = PublishSubject.create[Set[Addr]]

        Observable.zip[Set[Link], Set[Addr], Boolean](
            linksSubject, addrsSubject, makeFunc2((links, addrs) => true))
            .subscribe(TestObserver { _: Boolean => promise.trySuccess(OK)} )

        conn.linksList(linksSubject)
        conn.addrsList(addrsSubject)

        (desc, promise.future)
    }
    val ListLinksAndAddrs: LazyTest = () => listLinksAndAddrs

    val LinkTests: LazyTestSuite = Seq(ListlinkNumberTest, GetLinkTest,
        NewLinkNotificationTest)
    val AddrTests: LazyTestSuite = Seq(ListAddrTest, GetAddrTest,
        NewAddrNotificationTest)
    val RouteTests: LazyTestSuite = Seq(GetRouteTest, ListRouteTest,
        NewRouteNotificationTest)
    val NeighTests: LazyTestSuite = Seq(ListNeighTest, NewNeighNotificationTest)

    val CombinationTests: LazyTestSuite = Seq(ListLinksAndAddrs)
}

class RtnetlinkIntegrationTestBase extends RtnetlinkTest {
    override val conn = TestableSelectorBasedRtnetlinkConnection()

    def run(): Boolean = {
        var passed = true
        try {
            start()
            passed &= printReport(runLazySuite(LinkTests))
            passed &= printReport(runLazySuite(AddrTests))
            passed &= printReport(runLazySuite(RouteTests))
            passed &= printReport(runLazySuite(NeighTests))
            passed &= printReport(runLazySuite(CombinationTests))
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
