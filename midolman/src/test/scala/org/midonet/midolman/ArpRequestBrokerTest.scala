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
package org.midonet.midolman

import java.util.{ArrayDeque, HashMap, UUID}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

import akka.actor.ActorSystem

import org.apache.curator.test.TestingServer
import org.apache.zookeeper.CreateMode
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.concurrent.Eventually._
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.{Second, Span}
import org.midonet.cluster.backend.Directory
import org.midonet.cluster.backend.zookeeper.{ZkDirectory, ZkConnection}
import org.midonet.midolman.PacketWorkflow.{GeneratedLogicalPacket, GeneratedPacket}
import org.midonet.midolman.SimulationBackChannel.BackChannelMessage
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.simulation._
import org.midonet.midolman.state.ArpRequestBroker._
import org.midonet.midolman.state.{LegacyArpCacheImpl, _}
import org.midonet.midolman.util.ArpCacheHelper
import org.midonet.odp.flows.FlowKeys
import org.midonet.odp.{FlowMatch, Packet}
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder
import org.midonet.sdn.flows.FlowTagger
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.UnixClock
import org.midonet.util.eventloop.{Reactor, TryCatchReactor}

@RunWith(classOf[JUnitRunner])
class ArpRequestBrokerTest extends Suite
                             with BeforeAndAfter
                             with BeforeAndAfterAll
                             with ShouldMatchers {
    private final val ARP_RETRY = 1 * 1000
    private final val ARP_TIMEOUT = 6 * 1000
    private final val ARP_STALE = 100 * 1000
    private final val ARP_EXPIRATION = 200 * 1000

    implicit def strToIpv4(str: String): IPv4Addr = IPv4Addr.fromString(str)
    val ZK_RTT_TIMEOUT = Timeout(Span(1, Second))

    def uniqueRoot = "/test-" + UUID.randomUUID().toString
    var routerId: UUID = _

    val ZK_PORT = (10000 + 50000 * Math.random()).toInt

    var ts: TestingServer = _
    var directory: Directory = _
    var zkConn: ZkConnection = _
    var arpTable: ArpTable = _
    var arpCache: ArpCache = _
    var reactor: Reactor = _

    var remoteDir: Directory = _
    var remoteZkConn: ZkConnection = _
    var remoteArpTable: ArpTable = _
    var remoteArpCache: ArpCache = _
    var remoteReactor: Reactor = _

    var packetsEmitted = 0

    private val THEIR_IP = "180.0.1.1"
    private val NW_ADDR = "180.0.1.0"
    private val NW_CIDR = "180.0.1.0/24"
    private val MY_IP = "180.0.1.2"
    private val MY_MAC = MAC.fromString("02:0a:08:06:04:02")
    private val THEIR_MAC = MAC.random()

    val port = RouterPort(id = UUID.randomUUID(),
                          tunnelKey = 0,
                          portMac = MY_MAC,
                          routerId = routerId,
                          portAddressV4 = MY_IP,
                          portSubnetV4 = IPv4Subnet.fromCidr(NW_CIDR),
                          fip64vxlan = true)

    var router: Router = _

    implicit var actorSystem: ActorSystem = _
    implicit def ec: ExecutionContext = actorSystem.dispatcher

    val arps = new ArrayDeque[GeneratedPacket]()
    val invalidations = new ArrayDeque[FlowTag]()
    var arpBroker: ArpRequestBroker = _

    val clock = UnixClock.mock()

    val confValues = s"""
          |agent {
          |    arptable {
          |        arp_retry_interval = ${ARP_RETRY}ms
          |        arp_timeout = ${ARP_TIMEOUT}ms
          |        arp_stale = ${ARP_STALE}ms
          |        arp_expiration = ${ARP_EXPIRATION}ms
          |    }
          |}
        """.stripMargin

    val config = MidolmanConfig.forTests(confValues)

    val backChannel = new SimulationBackChannel {
        override def tell(message: BackChannelMessage): Unit =
            message match {
                case m: GeneratedPacket => arps.add(m)
                case m: FlowTag => invalidations.add(m)
            }

        override def poll(): BackChannelMessage = null

        override def hasMessages: Boolean = arps.size() > 0
    }

    implicit def packetContext: PacketContext = {
        val frame = Ethernet.random()
        val fmatch = new FlowMatch(FlowKeys.fromEthernetPacket(frame))
        fmatch.setInputPortNumber(1)
        val context = PacketContext.generated(-1, new Packet(frame, fmatch),
                                              fmatch)
        context.backChannel = backChannel
        context
    }

    override def beforeAll() {
        ts = new TestingServer(ZK_PORT)
        ts.start()

        actorSystem = ActorSystem()

        reactor = new TryCatchReactor("test-arp-req-broker", 1)
        remoteReactor = new TryCatchReactor("test-arp-req-broker-remote", 1)

        zkConn = new ZkConnection(ts.getConnectString, 10000, null, reactor)
        remoteZkConn = new ZkConnection(ts.getConnectString, 10000, null, remoteReactor)
        zkConn.open()
        remoteZkConn.open()

        directory = new ZkDirectory(zkConn, "", reactor)
        remoteDir = new ZkDirectory(zkConn, "", reactor)
    }

    override def afterAll() {
        reactor = null
        remoteReactor = null

        zkConn.close()
        zkConn = null
        remoteZkConn.close()
        remoteZkConn = null

        actorSystem.shutdown()
        actorSystem = null

        ts.stop()
        ts = null
    }

    before {
        clock.time = 0L
        routerId = UUID.randomUUID()
        val root = "/test-" + routerId.toString
        directory.add(root, "".getBytes, CreateMode.PERSISTENT)

        arpTable = new ArpTable(directory.getSubDirectory(root))
        arpTable.start()

        remoteArpTable = new ArpTable(directory.getSubDirectory(root))
        remoteArpTable.start()

        arpCache = new LegacyArpCacheImpl(arpTable, routerId, reactor)
        remoteArpCache = new LegacyArpCacheImpl(arpTable, routerId, reactor)
        arps.clear()
        invalidations.clear()

        arpBroker = new ArpRequestBroker(config, backChannel, clock)
        router = new Router(routerId, Router.Config(), null, null,
                            new HashMap[Int, UUID], arpCache, config.fip64)
    }

    after {
        arpTable.stop()
        remoteArpTable.stop()
        arpTable = null
        remoteArpTable = null
        arpCache = null
        remoteArpCache = null
    }

    private def expectEmitArp() {
        import PacketBuilder._
        arps should not be 'empty
        val pkt = arps.poll().asInstanceOf[GeneratedLogicalPacket]
        pkt.egressPort should === (port.id)
        val arpReq: Ethernet = { eth addr MY_MAC -> eth_bcast } <<
            { arp.req mac MY_MAC -> eth_zero ip MY_IP --> THEIR_IP}
        pkt.eth should === (arpReq)
        arps should be ('empty)
    }

    private def addMoreWaiters(waiters: List[Future[MAC]], howMany: Int = 10): List[Future[MAC]] = {
        if (howMany > 0) {
            val NotYetException(macFuture, _) = intercept[NotYetException] {
                arpBroker.get(THEIR_IP, port, router, -1)
            }
            addMoreWaiters(macFuture.asInstanceOf[Future[MAC]] :: waiters, howMany-1)
        } else {
            waiters
        }
    }

    def testUnrepliedArpLoop(): Unit = {
        val timeout = clock.time + ARP_TIMEOUT

        var futures = addMoreWaiters(List.empty)

        expectEmitArp()
        for (f <- futures)
            f should not be 'completed

        arpBroker.process()
        arps should be ('empty)

        var retryNo: Int = -1
        def loopDelta() = {
            retryNo += 1
            ((RETRY_MAX_BASE_JITTER + RETRY_JITTER_INCREMENT * retryNo) * ARP_RETRY).toLong
        }

        // go past the timeout by more than 1 retry interval
        while (clock.time < timeout + ARP_RETRY*2) {
            if (clock.time < timeout) {
                for (f <- futures)
                    f should not be 'completed
                futures = addMoreWaiters(futures)
            }

            clock.time += loopDelta()
            arpBroker.process()

            /*
             * we don't know the actual jitter and it sits somewhere between
             * 0.75 and 1.25 (and increasing with each iteration). In this test,
             * we increment the time by MAX_BASE_JITTER (1.25) plus the retry-no
             * modifier. If the actual jitter is 0.75, two arps could fit in a
             * single test loop increment:
             *
             *  - ARP with 0.75 base jitter at: 0.75, 1.6, 2,55.
             *  - Test loop starts uses 1.25 as the base jitter, and tests at:
             *    1.25, 2.60, 4.05.
             *
             * Thus it would get two arps in the second iteration: those emitted
             * at 1.6 and 2.55.
             *
             * For this reason we let the arp table emit either one or two arp
             * requests per iteration.
             */
            if (clock.time < timeout) {
                arps.size should be > 0
                arps.size should be <= 2
                arps.clear()
            } else {
                arps should be ('empty)
            }
        }

        for (f <- futures) {
            f should be ('completed)
            f.value.get should be ('failure)
        }
    }

    def testLocallyRepliedArpLoop(): Unit = {
        val timeout = clock.time + ARP_TIMEOUT

        var futures = addMoreWaiters(List.empty)
        arpBroker.process()

        while (clock.time < timeout/2) {
            for (i <- 1 to 4) {
                clock.time += ARP_RETRY / 2
                futures = addMoreWaiters(futures)
                arpBroker.process()
            }
        }

        arpBroker.set(THEIR_IP, THEIR_MAC, router)
        eventually(ZK_RTT_TIMEOUT) {
            arpBroker.get(THEIR_IP, port, router, -1) should be (THEIR_MAC)
            arpBroker.shouldProcess() should be (true)
        }

        arpBroker.process()
        for (f <- futures) {
            f.value should be (Some(Success(THEIR_MAC)))
        }
    }

    def testRemotelyRepliedArpLoop(): Unit = {
        val futures = addMoreWaiters(List.empty)
        arpBroker.process()

        ArpCacheHelper.feedArpCache(remoteArpCache, THEIR_IP, THEIR_MAC)
        eventually(ZK_RTT_TIMEOUT) {
            arpBroker.get(THEIR_IP, port, router, -1) should be (THEIR_MAC)
            arpBroker.shouldProcess() should be (true)
        }
        arpBroker.process()
        for (f <- futures) {
            f.value should be (Some(Success(THEIR_MAC)))
        }
    }

    def testInvalidatesFlowsRemotely(): Unit = {
        intercept[NotYetException] { arpBroker.get(THEIR_IP, port, router, -1) }
        ArpCacheHelper.feedArpCache(remoteArpCache, THEIR_IP, THEIR_MAC)
        eventually(ZK_RTT_TIMEOUT) {
            arpBroker.get(THEIR_IP, port, router, -1) should be (THEIR_MAC)
        }

        ArpCacheHelper.feedArpCache(remoteArpCache, THEIR_IP, MAC.random())
        eventually(ZK_RTT_TIMEOUT) {
            arpBroker.process()
            invalidations should have size 1
            invalidations.poll() should be (FlowTagger.tagForArpEntry(routerId, THEIR_IP))
        }
    }

    def testInvalidatesFlowsLocally(): Unit = {
        arpBroker.set(THEIR_IP, THEIR_MAC, router)
        eventually(ZK_RTT_TIMEOUT) {
            arpBroker.get(THEIR_IP, port, router, -1) should be (THEIR_MAC)
        }

        arpBroker.set(THEIR_IP, MAC.random(), router)
        eventually(ZK_RTT_TIMEOUT) {
            arpBroker.process()
            invalidations should have size 1
            invalidations.poll() should be (FlowTagger.tagForArpEntry(routerId, THEIR_IP))
        }
    }

    def testArpForStaleEntries(): Unit = {
        // write a mac entry and wait for the change to be committed without
        // using the broker directly. Then, let the broker process the
        // notification
        arpBroker.set(THEIR_IP, THEIR_MAC, router)
        eventually(ZK_RTT_TIMEOUT) {
            val e = arpCache.get(THEIR_IP)
            e should not be null
            e.mac should be (THEIR_MAC)
            arpBroker.shouldProcess() should be (true)
        }
        arpBroker.process()

        // let the entry go stale accounting for jitter.
        clock.time += (ARP_STALE + ARP_STALE * STALE_JITTER).toLong
        arpBroker.shouldProcess() should be (false)
        arpBroker.process()
        arps should be ('empty)

        // ask for the MAC and expec the broker to emit an ARP
        arpBroker.get(THEIR_IP, port, router, -1) should be (THEIR_MAC)
        expectEmitArp()

        // let one arp retry iteration go by, expect another ARP
        clock.time += (ARP_RETRY * RETRY_MAX_BASE_JITTER).toLong
        arpBroker.shouldProcess() should be (true)
        arpBroker.process()
        expectEmitArp()
        arpBroker.shouldProcess() should be (false)

        // refresh the cache entry, wait for the broker to receive the
        // notification. Then let time pass by and expect no new ARPs
        arpBroker.set(THEIR_IP, MAC.random(), router)
        eventually(ZK_RTT_TIMEOUT) {
            arpBroker.shouldProcess() should be (true)
        }
        arpBroker.process()
        clock.time += (ARP_RETRY * RETRY_MAX_BASE_JITTER).toLong * 2
        arpBroker.process()
        arps should be ('empty)

        // let another retry interval go by without any ARPs on the wire
        clock.time += (ARP_RETRY * RETRY_MAX_BASE_JITTER).toLong * 2
        arpBroker.shouldProcess() should be (false)
        arpBroker.process()
        arps should be ('empty)
    }

    def testExpiresEntries(): Unit = {
        arpBroker.set(THEIR_IP, THEIR_MAC, router)
        eventually(ZK_RTT_TIMEOUT) {
            arpBroker.get(THEIR_IP, port, router, -1) should be (THEIR_MAC)
        }
        arpBroker.process()
        arps.clear()

        clock.time += ARP_EXPIRATION
        arpBroker.process()
        eventually(ZK_RTT_TIMEOUT) {
            arpCache.get(THEIR_IP) should be (null)
        }
        arps should be ('empty)
    }
}
