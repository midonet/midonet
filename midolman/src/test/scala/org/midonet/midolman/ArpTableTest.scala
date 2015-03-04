/*
 * Copyright 2014 Midokura SARL
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

import java.util.{LinkedList, UUID}
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.PacketWorkflow.NoOp
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.midolman.simulation._
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPacket
import org.midonet.midolman.state.ArpCacheEntry
import org.midonet.midolman.state.ReplicatedMap.Watcher
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.devices.RouterPort
import org.midonet.midolman.util.MidolmanSpec
import org.midonet.midolman.util.mock.MessageAccumulator
import org.midonet.odp.FlowMatch
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder
import org.midonet.util.UnixClock

@RunWith(classOf[JUnitRunner])
class ArpTableTest extends MidolmanSpec {
    // these should reliably give us two retries, no more, no less.
    private final val ARP_RETRY_SECS = 2
    private final val ARP_TIMEOUT_SECS = 3
    private final val ARP_STALE_SECS = 5
    private final val ARP_EXPIRATION_SECS = 10

    private val hisIp = "180.0.1.1"
    private val nwAddr = "180.0.1.0"
    private val myIp = "180.0.1.2"
    private val myMac = MAC.fromString("02:0a:08:06:04:02")

    private var router: Router = null
    private var uplinkPort: RouterPort = null

    implicit def strToIpv4(str: String): IPv4Addr = IPv4Addr.fromString(str)

    val confStr =
        s"""
          |datapath.max_flow_count = 10
          |arptable.arp_retry_interval = ${ARP_RETRY_SECS}s
          |arptable.arp_timeout = ${ARP_TIMEOUT_SECS}s
          |arptable.arp_stale = ${ARP_STALE_SECS}s
          |arptable.arp_expiration = ${ARP_EXPIRATION_SECS}s
        """.stripMargin

    override protected def fillConfig(config: Config) = {
        super.fillConfig(ConfigFactory.parseString(confStr).withFallback(config))
    }

    private def buildTopology() {
        newHost("myself", hostId)
        val clusterRouter = newRouter("router")
        val clusterPort = newRouterPort(clusterRouter, myMac, myIp, nwAddr, 24)
        stateStorage.setPortLocalAndActive(clusterPort.getId, hostId, true)
        newRoute(clusterRouter, "0.0.0.0", 0, "0.0.0.0", 0, NextHop.PORT, clusterPort.getId, hisIp, 1)

        router = fetchDevice(clusterRouter)
        uplinkPort = fetchDevice(clusterPort)
    }

    registerActors(VirtualTopologyActor -> (() => new VirtualTopologyActor()
                                                  with MessageAccumulator))

    override def beforeTest() {
        scheduler.runAll()
        System.getProperties.setProperty(UnixClock.USE_MOCK_CLOCK_PROPERTY, "yes")
        buildTopology()
        scheduler.runAll()
    }

    override def afterTest() {
        scheduler.runAll()
        val p = System.getProperties.remove(UnixClock.USE_MOCK_CLOCK_PROPERTY)
    }

    private def extractMac(macTry: Try[MAC])(implicit timeout: Duration = 100 millis): MAC =
        macTry match {
            case Success(mac) => mac
            case Failure(NotYetException(f, _)) => Await.result(f.mapTo[MAC], timeout)
            case Failure(e) => throw e
        }

    private val arps = new LinkedList[GeneratedPacket]()
    implicit private def dummyPacketContext: PacketContext = {
        val context = new PacketContext(0, null, new FlowMatch())
        context.packetEmitter = new PacketEmitter(arps, actorSystem.deadLetters)
        context
    }

    private def expectEmittedPacket(ctx: PacketContext, port: UUID): Ethernet = {
        ctx.packetEmitter.pendingPackets should be (1)
        val generatedPacket = ctx.packetEmitter.poll()
        generatedPacket.egressPort should === (port)
        generatedPacket.eth
    }

    private def expectEmitArp(port: UUID, fromMac: MAC, fromIp: String, toIp: String) {
        import PacketBuilder._
        arps.size() should be (1)
        val generatedPacket = arps.poll()
        generatedPacket.egressPort should === (port)
        val frame = generatedPacket.eth
        val arpReq: Ethernet = { eth addr fromMac -> eth_bcast } <<
            { arp.req mac fromMac -> eth_zero ip fromIp --> toIp}
        frame should === (arpReq)
    }

    private def expectReplyArp(ctx: PacketContext, port: UUID, fromMac: MAC,
                               toMac: MAC, fromIp: String, toIp: String) {
        import PacketBuilder._
        val frame = expectEmittedPacket(ctx, port)
        val reply: Ethernet = { eth addr fromMac -> toMac } <<
            { arp.reply mac fromMac -> toMac ip fromIp --> toIp}
        frame should === (reply)
    }

    def advanceAndGetTime(howManySeconds: Long): Long = {
        UnixClock.MOCK.time += howManySeconds * 1000
        scheduler.runOverdueTasks()
        UnixClock.time
    }

    feature("ARP Table") {
        scenario("ARP request is fulfilled locally") {
            val mac = MAC.fromString("aa:bb:aa:cc:dd:cc")
            val arpTry = Try(router.arpTable.get(hisIp, uplinkPort))
            expectEmitArp(uplinkPort.id, uplinkPort.portMac,
                uplinkPort.portAddr.getAddress.toString, hisIp)
            router.arpTable.set(hisIp, mac)
            extractMac(arpTry) should be (mac)
        }

        scenario("ARP request is fulfilled remotely") {
            val mac = MAC.fromString("fe:fe:fe:da:da:da")

            val arpTable = router.arpTable.asInstanceOf[ArpTableImpl]
            val arpCache = arpTable.arpCache.asInstanceOf[Watcher[IPv4Addr,
                ArpCacheEntry]]

            val macTry = Try(router.arpTable.get(hisIp, uplinkPort))
            arpCache.processChange(hisIp, null,
                new ArpCacheEntry(mac, UnixClock.time + 60*1000, UnixClock.time + 30*1000, 0))
            extractMac(macTry) should be (mac)
        }

        scenario("Responds to bcast ARP requests") {
            import PacketBuilder._

            val hisMac = MAC.fromString("ab:cd:ef:ab:cd:ef")

            val req: Ethernet = { eth addr hisMac -> eth_bcast} <<
                { arp.req mac hisMac -> eth_zero ip hisIp --> myIp }
            val (ctx, action) = simulateDevice(router, req, uplinkPort.id)
            action should === (NoOp)
            expectReplyArp(ctx, uplinkPort.id, myMac, hisMac, myIp.toString, hisIp.toString)

            val macTry = Try(router.arpTable.get(hisIp, uplinkPort))
            extractMac(macTry) should be (hisMac)
        }

        scenario("Responds to unicast ARP requests") {
            import PacketBuilder._

            val hisMac = MAC.fromString("ab:cd:ef:ab:cd:ef")

            val req: Ethernet = { eth addr hisMac -> myMac} <<
                { arp.req mac hisMac -> myMac ip hisIp --> myIp }
            val (ctx, action) = simulateDevice(router, req, uplinkPort.id)
            action should === (NoOp)
            expectReplyArp(ctx, uplinkPort.id, myMac, hisMac, myIp.toString, hisIp)

            val macTry = Try(router.arpTable.get(hisIp, uplinkPort))
            extractMac(macTry) should be (hisMac)
        }

        scenario("ARP requests time out") {

            val macTry = Try(router.arpTable.get(hisIp, uplinkPort))
            expectEmitArp(uplinkPort.id, myMac, myIp, hisIp)

            advanceAndGetTime(ARP_RETRY_SECS - 1)

            advanceAndGetTime(1)
            expectEmitArp(uplinkPort.id, myMac, myIp, hisIp)

            advanceAndGetTime(ARP_RETRY_SECS)

            try {
                extractMac(macTry)(100 milliseconds)
                fail("MAC should not be known, ARP goes unreplied")
            } catch {
                case ex: ArpTimeoutException =>
                case _: Throwable =>
                    fail("The thrown exception should be ArpTimeoutException.")
            }
        }

        scenario("ARP requests are retried") {
            val hisMac = MAC.fromString("77:aa:66:bb:55:cc")

            val macTry = Try(router.arpTable.get(hisIp, uplinkPort))
            expectEmitArp(uplinkPort.id, myMac, myIp, hisIp)

            advanceAndGetTime(ARP_RETRY_SECS)
            expectEmitArp(uplinkPort.id, myMac, myIp, hisIp)

            router.arpTable.set(hisIp, hisMac)
            extractMac(macTry) should be (hisMac)

            advanceAndGetTime(ARP_RETRY_SECS)
        }

        scenario("ARP entries expire") {
            val mac = MAC.fromString("aa:bb:aa:cc:dd:cc")

            var macTry = Try(router.arpTable.get(hisIp, uplinkPort))
            expectEmitArp(uplinkPort.id, myMac, myIp.toString, hisIp)
            router.arpTable.set(hisIp, mac)
            extractMac(macTry) should be (mac)

            advanceAndGetTime(ARP_STALE_SECS/2)
            router.arpTable.set(hisIp, mac)

            macTry = Try(router.arpTable.get(hisIp, uplinkPort))
            extractMac(macTry) should be (mac)

            advanceAndGetTime(ARP_EXPIRATION_SECS - ARP_STALE_SECS/2 + 1)
            macTry = Try(router.arpTable.get(hisIp, uplinkPort))
            expectEmitArp(uplinkPort.id, myMac, myIp.toString, hisIp)

            try {
                // No one replies to the ARP request, so the get should return
                // null. We have to wait long enough to give it a chance to time
                // out and complete the promise with null.
                advanceAndGetTime(ARP_TIMEOUT_SECS)
                extractMac(macTry)
                fail("Future should timeout since ARP is not replied")
            } catch {
                case ex: ArpTimeoutException =>
                case ex: Throwable =>
                    fail("The thrown exception should be TimeoutException but it " +
                        "was " + ex.getMessage, ex)
            }
        }
    }
}
