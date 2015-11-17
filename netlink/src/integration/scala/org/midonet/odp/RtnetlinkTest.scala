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

package org.midonet.odp

import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousCloseException, ClosedByInterruptException}

import scala.collection.mutable
import scala.concurrent.Promise
import scala.sys.process._

import com.typesafe.scalalogging.Logger
import org.junit.runner.RunWith
import org.scalatest.concurrent._
import org.scalatest.junit.JUnitRunner
import org.scalatest.time._
import org.scalatest.{BeforeAndAfterAll, FeatureSpec, Matchers}
import org.slf4j.LoggerFactory
import rx.subjects.PublishSubject
import rx.{Observable, Observer}

import org.midonet.netlink.rtnetlink._
import org.midonet.netlink.{NetlinkChannelFactory, NetlinkProtocol, NetlinkUtil, _}
import org.midonet.odp.util.TapWrapper
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.IntegrationTests._
import org.midonet.util.concurrent.NanoClock
import org.midonet.util.functors._

private[odp] object NotificationTestObserver {
    def apply(condition: ByteBuffer => Boolean)
             (implicit promise: Promise[String] = Promise[String]()) = {
        val obs = new NotificationTestObserver
        obs.check = condition
        obs
    }
}

private[odp]
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
        val nlType = buf.getShort(NetlinkMessage.NLMSG_TYPE_OFFSET)
        if (nlType < NLMessageType.NLMSG_MIN_TYPE) {
            return
        }

        val seq = buf.getInt(NetlinkMessage.NLMSG_SEQ_OFFSET)
        if (seq != 0 && (nlType != Rtnetlink.Type.NEWADDR &&
            nlType != Rtnetlink.Type.DELADDR)) {
            return
        }
        buf.position(NetlinkMessage.HEADER_SIZE)
        handleNotification(nlType, buf)
    }
}

class TestableRtnetlinkConnection(channel: NetlinkChannel,
                                  maxPendingRequests: Int,
                                  maxRequestSize: Int,
                                  clock: NanoClock)
    extends RtnetlinkConnection(channel, maxPendingRequests,
        maxRequestSize, clock) {
    import RtnetlinkTest._

    val testNotificationObserver: NotificationTestObserver =
        TestableNotificationObserver
    private val notificationChannel =
        (new NetlinkChannelFactory).create(true, NetlinkProtocol.NETLINK_ROUTE,
            notificationGroups = NetlinkUtil.DEFAULT_RTNETLINK_GROUPS)
    private val notificationReader: NetlinkReader =
        new NetlinkReader(notificationChannel)
    private val name: String = this.getClass.getName + pid
    private val notificationObserver: Observer[ByteBuffer] =
        testNotificationObserver

    private
    val rtnetlinkNotificationReadThread = new Thread(s"$name-notification") {
        override def run(): Unit = try {
            NetlinkUtil.readNetlinkNotifications(notificationChannel,
                notificationReader, NetlinkMessage.HEADER_SIZE,
                notificationObserver)
        } catch {
            case ex @ (_: InterruptedException |
                       _: ClosedByInterruptException|
                       _: AsynchronousCloseException) =>
                log.info(s"$ex on rtnetlink notification channel, STOPPING")
            case ex: Exception =>
                log.error(s"$ex on rtnetlink notification channel, ABORTING",
                    ex)
        }
    }

    def start(): Unit = {
        rtnetlinkNotificationReadThread.setDaemon(true)
        rtnetlinkNotificationReadThread.start()
    }

    def stop(): Unit = {
        rtnetlinkNotificationReadThread.interrupt()
        notificationChannel.close()
    }
}

object RtnetlinkTest {
    val TestIpAddr = "192.168.42.1"
    val TestAnotherIpAddr = "192.168.42.10"
    val TestNeighbourIpAddr = "192.168.42.42"
    val TestNeighbourMacAddr = MAC.random()
    val TestTimeoutSpan = Span(2, Seconds)

    val log = Logger(LoggerFactory.getLogger(classOf[RtnetlinkTest]))

    val TestableNotificationObserver: NotificationTestObserver = {
        implicit val promise = Promise[String]()
        new  NotificationTestObserver
    }
}

@RunWith(classOf[JUnitRunner])
class RtnetlinkTest extends FeatureSpec
                    with BeforeAndAfterAll
                    with Matchers
                    with ScalaFutures {
    import RtnetlinkTest._

    val conn = new TestableRtnetlinkConnection(
        (new NetlinkChannelFactory).create(blocking = true,
            protocol = NetlinkProtocol.NETLINK_ROUTE,
            notificationGroups = NetlinkUtil.NO_NOTIFICATION),
        NetlinkUtil.DEFAULT_MAX_REQUESTS,
        NetlinkUtil.DEFAULT_MAX_REQUEST_SIZE,
        NanoClock.DEFAULT)

    val tapName = "rtnetlink_test"
    // Tap name length should be less than 15.
    var tapId: Int = 0
    var tap: TapWrapper = null

    override def beforeAll(): Unit = {
        (s"ip link show $tapName".! == 0) && (s"ip link del $tapName".! == 0)
        tap = new TapWrapper(tapName, true)
        tap.up()
        tapId = (s"ip link show $tapName" #|
            "head -n 1" !!).takeWhile(_ != ':').trim.toInt
        conn.start()
    }

    override def afterAll(): Unit = {
        conn.stop()
        tap.down()
        tap.remove()
    }

    feature("rtnetlink operations for links") {
        scenario("""the number of listed links should equal to the result of
                   |`ip link list`""".stripMargin.replaceAll("\n", " ")) {
            val obs = TestObserver { links: Set[Link] =>
                val ipLinkNum = ("ip link list" #| "wc -l" !!).trim.toInt / 2
                links.size == ipLinkNum
            }
            conn.linksList(obs)
            while (!obs.isCompleted) {
                try {
                    conn.requestBroker.readReply()
                } catch {
                    case ex: Exception =>
                        log.error("Error happened on reading rtnetlink " +
                            s"messages: $ex")
                }
            }

            whenReady(obs.test, timeout(TestTimeoutSpan)) { result: String =>
                result should be (OK)
            }
        }

        scenario("""the interface id should be identical to the result of `ip
                   |link show`.""".stripMargin.replaceAll("\n", " ")) {
            val obs = TestObserver { link: Link =>
                link.ifi.index == tapId
            }
            conn.linksGet(tapId, obs)
            while (!obs.isCompleted) {
                try {
                    conn.requestBroker.readReply()
                } catch {
                    case ex: Exception =>
                        log.error("Error happened on reading rtnetlink " +
                            "messages", ex)
                }
            }

            whenReady(obs.test, timeout(TestTimeoutSpan)) { result: String =>
                result should be (OK)
            }
        }

        scenario("""the link created by `ip link add` should be notified to
                   |the notification observer.
                 """.stripMargin.replaceAll("\n", " ")) {
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
                                    < (linkNum + 1)) {
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
                    fail(TestPrepareException)
                }
            }


            try {
                whenReady(promise.future, timeout(TestTimeoutSpan)) { result =>
                    result should be (OK)
                }
            } finally {
                conn.testNotificationObserver.clear()
                s"ip link del ${tapName}2".!
            }
        }
    }

    feature("rtnetlink operations for addresses") {
        scenario("""the number of addresses should be identical to the result
                   |of `ip addr list`.""".stripMargin.replaceAll("\n", " ")) {
            val obs = TestObserver { addrs: Set[Addr] =>
                val ipAddrsNum = ("ip address list" #|
                    "grep inet" #| "wc -l" !!).trim.toInt
                addrs.size == ipAddrsNum
            }
            conn.addrsList(obs)
            while (!obs.isCompleted) {
                try {
                    conn.requestBroker.readReply()
                } catch {
                    case ex: Exception =>
                        log.error("Error happened on reading rtnetlink " +
                            "messages", ex)
                }
            }

            whenReady(obs.test, timeout(TestTimeoutSpan)) { result: String =>
                result should be (OK)
            }
        }

        scenario("""the address of the interface should be identical to what
                   |is created by `ip addr add`.
                 """.stripMargin.replaceAll("\n", " ")) {
            if (s"ip address add $TestIpAddr dev $tapName".! != 0) {
                fail(TestPrepareException)
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
            }

            conn.addrsList(obs)
            while (!obs.isCompleted) {
                try {
                    conn.requestBroker.readReply()
                } catch {
                    case ex: Exception =>
                        log.error("Error happened on reading rtnetlink " +
                            "messages", ex)
                }
            }

            try {
                whenReady(obs.test, timeout(TestTimeoutSpan)) { result: String =>
                    result should be (OK)
                }
            } finally {
                s"ip address flush dev $tapName".!
            }
        }

        scenario("""the address created by `ip address add` should be notified
                   |to the notification obsever .
                 """.stripMargin.replaceAll("\n", " ")) {
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
                                    < (addrNum + 1)) {
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
                    fail(TestPrepareException)
                }
            }

            try {
                whenReady(promise.future, timeout(TestTimeoutSpan)) { result =>
                    result should be (OK)
                }
            } finally {
                s"ip address flush dev $tapName".!
            }
        }
    }


    feature("rtnetlink operations for routes") {
        scenario("""the number of IPv4 entries of the default routing table
                   |should be identical to the result of `ip route list`.
                 """.stripMargin.replaceAll("\n", " ")) {
            val obs = TestObserver { routes: Set[Route] =>
                val routesNum = ("ip route list" #| "wc -l" !!).trim.toInt
                val filteredRoutes = routes.filter(r =>
                    r.rtm.table == 254.toByte &&
                        r.rtm.family == Addr.Family.AF_INET)
                filteredRoutes.size == routesNum
            }

            conn.routesList(obs)
            while (!obs.isCompleted) {
                try {
                    conn.requestBroker.readReply()
                } catch {
                    case ex: Exception =>
                        log.error("Error happened on reading rtnetlink " +
                            "messages", ex)
                }
            }

            whenReady(obs.test, timeout(TestTimeoutSpan)) { result: String =>
                result should be (OK)
            }
        }

        scenario("""the route created by `ip route add` should be notified to
                   |the notification observer.
                 """.stripMargin.replaceAll("\n", " ")) {
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
                                if (notificationObserver.notifiedRoutes.size <
                                    (routeNum + 1)) {
                                    promise.tryFailure(UnexpectedResultException)
                                } else {
                                    promise.trySuccess(OK)
                                }
                            case _ =>
                        }
                }
                notificationObserver.notifiedRoutes.clear()
                routeNum = notificationObserver.notifiedRoutes.size

                if ((s"ip address add $TestIpAddr dev $tapName".! != 0) &&
                    (s"ip route add $dstSubnet via $TestIpAddr dev $tapName".! != 0)) {
                    fail(TestPrepareException)
                }
            }

            try {
                whenReady(promise.future, timeout(TestTimeoutSpan)) { result =>
                    result should be (OK)
                }
            } finally {
                s"ip route flush dev $tapName".!
                s"ip address flush dev $tapName".!
            }
        }

        scenario("""the address of the interface should be identical to what
                   |created by `ip route add`.
                 """.stripMargin.replaceAll("\n", " ")) {
            val promise = Promise[String]()
            val dst = "192.168.42.0"
            val dstSubnet = s"$dst/24"
            if ((s"ip address add $TestIpAddr dev $tapName".! != 0) &&
                (s"ip route add $dstSubnet via $TestIpAddr dev $tapName".! != 0)) {
                fail(TestPrepareException)
            }
            val obs = TestObserver { route: Route =>
                route.rtm.dstLen == 32 &&
                    route.rtm.family == Route.Family.AF_INET &&
                    route.dst == IPv4Addr.fromString(dst)
            }(promise)

            conn.routesGet(IPv4Addr.fromString(dst), obs)
            while (!obs.isCompleted) {
                try {
                    conn.requestBroker.readReply()
                } catch {
                    case ex: Exception =>
                        log.error("Error happened on reading rtnetlink " +
                            "messages", ex)
                }
            }

            try {
                whenReady(promise.future, timeout(TestTimeoutSpan)) { result =>
                    result should be (OK)
                }
            } finally {
                s"ip route flush dev $tapName".!
                s"ip address flush dev $tapName".!
            }
        }
    }


    feature("rtnetlink operations for neighbours") {
        scenario("""the number of neighbours should be identical to the result
                   |of `ip neigh list`.
                 """.stripMargin.replaceAll("\n", " ")) {
            val obs = TestObserver { neighs: Set[Neigh] =>
                val ipNeighsNum = ("ip neigh list" #| "grep REACHABLE" #|
                    "wc -l" !!).trim.toInt
                val filteredNeighs = neighs.filter(
                    _.ndm.state == Neigh.State.NUD_REACHABLE)
                filteredNeighs.size == ipNeighsNum
            }
            conn.neighsList(obs)
            while (!obs.isCompleted) {
                try {
                    conn.requestBroker.readReply()
                } catch {
                    case ex: Exception =>
                        log.error("Error happened on reading rtnetlink " +
                            "messages", ex)
                }
            }

            whenReady(obs.test, timeout(TestTimeoutSpan)) { result: String =>
                result should be (OK)
            }
        }

        scenario("""the neighbour created by `ip neighbour add` should be
                   |notified to the notification observer
                 """.stripMargin.replaceAll("\n", " ")) {
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
                                if (notificationObserver.notifiedRoutes.size <
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
                    fail(TestPrepareException)
                }
            }

            try {
                whenReady(promise.future, timeout(TestTimeoutSpan)) { result =>
                    result should be (OK)
                }
            } finally {
                s"ip neighbour flush dev $tapName".!
            }
        }
    }

    feature("rtnetlink operations for combination of multiple resources") {
        scenario("""listing addresses following right after listing links
                   |should not experience [16] Resource or device busy.
                 """.stripMargin.replaceAll("\n", " ")) {
            implicit val promise = Promise[String]()

            val linksSubject = PublishSubject.create[Set[Link]]
            val addrsSubject = PublishSubject.create[Set[Addr]]

            val obs = TestObserver { _: Boolean => promise.trySuccess(OK) }
            Observable.zip[Set[Link], Set[Addr], Boolean](
                linksSubject, addrsSubject, makeFunc2((links, addrs) => true))
                .subscribe(obs)

            conn.linksList(linksSubject)

            var done = false
            val markDone = makeAction0 { done = true }

            linksSubject.finallyDo(markDone)
            while (!done) {
                try {
                    conn.requestBroker.readReply()
                } catch {
                    case ex: Exception =>
                        log.error("Error happened on reading rtnetlink " +
                            "messages", ex)
                }
            }

            done = false
            conn.addrsList(addrsSubject)
            addrsSubject.finallyDo(markDone)
            while (!done) {
                try {
                    conn.requestBroker.readReply()
                } catch {
                    case ex: Exception =>
                        log.error("Error happened on reading rtnetlink " +
                            "messages", ex)
                }
            }

            whenReady(obs.test, timeout(TestTimeoutSpan)) { result: String =>
                result should be (OK)
            }
        }
    }
}

