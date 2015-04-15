/*
 * Copyright 2014, 2015 Midokura SARL
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
package org.midonet.midolman.state

import java.util
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.{ArrayDeque, Comparator, PriorityQueue, UUID}
import scala.concurrent.{Promise, Future}
import scala.util.Random

import com.google.common.collect.ArrayListMultimap
import org.slf4j.LoggerFactory
import com.typesafe.scalalogging.Logger

import org.midonet.cluster.client.ArpCache
import org.midonet.midolman.NotYetException
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.flows.FlowInvalidator
import org.midonet.midolman.simulation._
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPacket
import org.midonet.midolman.state.ArpRequestBroker.MacChange
import org.midonet.midolman.topology.devices.RouterPort
import org.midonet.packets.{ARP, Ethernet, IPv4Addr, MAC}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.UnixClock
import org.midonet.util.functors.Callback3

object ArpRequestBroker {
    case class MacChange(ip: IPv4Addr, oldMac: MAC, newMac: MAC)
}

/**
 * Manages ARP requests for all MidoNet routers.
 *
 * Meant to be used in a single thread, each simulation thread should have
 * its own instance.
 *
 * It lets simulation threads query and write to the ARP table. Based on
 * those calls, it sends ARP requests as appropriate and manages expiration
 * of the entries it writes.
 *
 * ARP requests are generated with no coordination with other threads or
 * agents. This means that, specially when a MAC is unknown, two threads or
 * agents may decide to ARP at the same time for the IP address. To reduce
 * this effect outside of the 1st ARP request, the implementation introduces
 * jitter to the staleness and ARP retry intervals.
 *
 * Each broker is responsible for the expiration of the ARP table entries it
 * writes
 */
trait ArpRequestBroker {
    /*
     * Queries the ARP table, throwing a NotYetException if the MAC is unknown.
     *
     * It will initiate a new ARP request loop if the MAC is unknown or stale
     * and the broker has not already initiated an ARP request loop for this IP
     * address.
     */
    @throws(classOf[NotYetException])
    def get(ip: IPv4Addr, port: RouterPort, router: Router)
           (implicit pktContext: PacketContext): MAC
    def set(ip: IPv4Addr, mac: MAC, router: Router)
    def setAndGet(ip: IPv4Addr, mac: MAC, port: RouterPort, router: Router): Future[MAC]
}

class ArpRequestBrokerImpl(emitter: PacketEmitter,
                                 config: MidolmanConfig,
                                 invalidator: FlowInvalidator,
                                 clock: UnixClock = UnixClock()) extends ArpRequestBroker {

    private val brokers = new util.HashMap[UUID, SingleRouterArpRequestBroker]()

    def broker(router: Router): SingleRouterArpRequestBroker = {
        brokers.get(router.id) match {
            case null =>
                val broker = new SingleRouterArpRequestBroker(router.id,
                        router.arpCache, emitter, config, invalidator, clock)
                brokers.put(router.id, broker)
                broker
            case broker => broker
        }
    }

    override def get(ip: IPv4Addr, port: RouterPort, router: Router)
           (implicit pktContext: PacketContext): MAC = {
        broker(router).get(ip, port)
    }

    override def set(ip: IPv4Addr, mac: MAC, router: Router) = {
        broker(router).set(ip, mac)
    }

    override def setAndGet(ip: IPv4Addr, mac: MAC, port: RouterPort,
            router: Router): Future[MAC] = {
        broker(router).setAndGet(ip, mac, port)
    }

    def process(): Unit = {
        val tables = brokers.values.iterator()
        while (tables.hasNext)
            tables.next().process()
    }
}

class SingleRouterArpRequestBroker(id: UUID,
                                   arpCache: ArpCache,
                                   emitter: PacketEmitter,
                                   config: MidolmanConfig,
                                   invalidator: FlowInvalidator,
                                   clock: UnixClock = UnixClock()) {

    val log = Logger(LoggerFactory.getLogger(s"org.midonet.devices.router.arptable-$id"))

    private val random = new Random()

    private val LOOP_COMPARE = new Comparator[ArpLoop] {
        override def compare(a: ArpLoop, b: ArpLoop) = {
            if (a.nextTry < b.nextTry) -1
            else if (a.nextTry == b.nextTry) 0
            else 1
        }
    }

    /*
     * Set of IPv4 addresses we are currently ARP'ing for. Used as a guard
     * against the initiation of multiple ARP request loops.
     */
    private val arpLoops = new util.HashSet[IPv4Addr]()

    /*
     * Priority queue of currently active ARP request loops, ordered by
     * time to their next retry. It will be checked periodically to emit
     * any pending ARP request or expired any timed out loops.
     */
    private val arpLoopQ= new PriorityQueue[ArpLoop](LOOP_COMPARE)

    /*
     * Queue of ARP entry expirations. It contains in FIFO order, all the
     * arp entries created by this ArpRequestBroker. In other words, entries
     * for which set() was called on this specific instance. This instance is
     * responsible for the expiration of the entries it has itself created. It
     * does not need to worry about other entries because they are ephemeral
     * and guaranteed to disappear if other nodes fail.
     */
    private val expirationQ= new ArrayDeque[(Long, IPv4Addr)]()

    /*
     * Unfulfilled MAC promises for all packets that had to be suspended because
     * of a miss in the ARP table.
     */
    private val arpWaiters = ArrayListMultimap.create[IPv4Addr, Promise[MAC]]()

    /*
     * Privately managed back-channel through which this class hears of newly
     * learned MACs. This class will install a callback in the underlying
     * ArpCache's reactor to write events to this concurrent queue. It will later
     * process this events sequentially when process() is invoked.
     */
    private val macsDiscovered = new ConcurrentLinkedQueue[MacChange]()

    /**
     * Notify this ArpRequestBroker that the ArpTable has discovered a new
     * MAC - IP address association. May be called from any thread.
     */
    arpCache.notify(new Callback3[IPv4Addr, MAC, MAC] {
        override def call(ip: IPv4Addr, oldMac: MAC, newMac: MAC): Unit = {
            macsDiscovered.add(MacChange(ip, oldMac, newMac))
        }
    })

    /*
     * Staleness jitter: if there are 30 minutes between configured staleness
     * and expiration. Jitter will be somewhere between +/- 3 minutes.
     */
    private val stalenessJitter: Long = {
        val staleToExp = config.arptable.expiration - config.arptable.stale
        (staleToExp.abs * .2 * (random.nextDouble() - 0.5)).toLong
    }

    private def shouldArp(cacheEntry: ArpCacheEntry): Boolean = {
        (cacheEntry ne null) && (cacheEntry.macAddr ne null) &&
            (clock.time > (cacheEntry.stale + stalenessJitter))
    }


    /*
     * Checks back-channels (which are private, but exist) and runs all
     * ARP table book keeping tasks. Including expiries and ARP request
     * generation.
     */
    def process(): Unit = {
        processNewMacs()
        processArpLoops()
        processExpirations()
    }

    /*
     * Queries the ARP table, returning a MAC or throwing a NotYetException
     * if the MAC is unknownn.
     */
    @throws(classOf[NotYetException])
    def get(ip: IPv4Addr, port: RouterPort)
           (implicit pktContext: PacketContext): MAC = {

        val cacheEntry = arpCache.get(ip)

        if (shouldArp(cacheEntry))
            arpForAddress(ip, cacheEntry, port)

        if ((cacheEntry ne null) && (cacheEntry.macAddr ne null))
            cacheEntry.macAddr
        else
            throw new NotYetException(waitForArpEntry(ip), s"MAC for IP $ip unknown, suspending during ARP")
    }

    private def waitForArpEntry(ip: IPv4Addr): Future[MAC] = {
        null
    }

    private def arpForAddress(ip: IPv4Addr, entry: ArpCacheEntry, port: RouterPort): Unit = {
        if (arpLoops.contains(ip))
            return

        val loop = new ArpLoop(ip, port)

        val arp = makeArpRequest(port.portMac, port.portAddr.getAddress, ip)
        emitter.schedule(GeneratedPacket(port.id, arp))

        arpLoops.add(ip)
        arpLoopQ.add(loop)
    }

    /**
     * Writes a learned MAC to the ARP table. It's a NO-OP unless the entry was
     * unknown or stale.
     */
    def set(ip: IPv4Addr, mac: MAC): Unit = {
        val entry = arpCache.get(ip)

        if (!shouldArp(entry)) {
            log.debug("Skipping write to ArpCache because a non-stale " +
                "entry for {} with the same value ({}) exists.", ip, mac)
        } else {
            log.debug("Got address for {}: {}", ip, mac)
            val now = clock.time
            val entry = new ArpCacheEntry(mac, now + config.arptable.expiration,
                                               now + config.arptable.stale, 0)
            arpCache.add(ip, entry)
            expirationQ.add((entry.expiry, ip))
        }
    }

    def setAndGet(ip: IPv4Addr, mac: MAC, port: RouterPort): Future[MAC] = {
        set(ip, mac)
        val entry = arpCache.get(ip)

        if ((entry ne null) && (entry.macAddr ne null))
            Future.successful(entry.macAddr)
        else
            waitForArpEntry(ip)
    }

    private def processExpirations() {
        while ((!expirationQ.isEmpty) && (expirationQ.peek()._1 < clock.time)) {
            val (_, ip) = expirationQ.poll()
            val entry = arpCache.get(ip)
            if (entry.expiry < clock.time)
                arpCache.remove(ip)
        }
    }

    private def processArpLoops() {
        val now = clock.time
        while ((!arpLoopQ.isEmpty) && arpLoopQ.peek().nextTry < now) {
            val loop = arpLoopQ.poll()
            if (loop.shouldRetry) {
                val arp = makeArpRequest(loop.port.portMac,
                                         loop.port.portAddr.getAddress, loop.ip)
                emitter.schedule(GeneratedPacket(loop.port.id, arp))
                loop.tick()
                arpLoopQ.add(loop)
            } else {
                arpLoops.remove(loop.ip)
                breakPromises(loop.ip)
            }
        }
    }

    private def breakPromises(ip: IPv4Addr): Unit = {
        val waiters = arpWaiters.removeAll(ip).iterator()
        while (waiters.hasNext)
            waiters.next().tryFailure(ArpTimeoutException(id, ip))
    }

    private def processNewMacs(): Unit = while (!macsDiscovered.isEmpty) {
        val MacChange(ip, oldMac, newMac) = macsDiscovered.poll()
        if (newMac ne null) {
            if (oldMac ne null) {
                log.debug("Invalidating flows for {} in router {}", ip, id)
                invalidator.scheduleInvalidationFor(FlowTagger.tagForDestinationIp(id, ip))
            }
            val waiters = arpWaiters.removeAll(ip).iterator()
            while (waiters.hasNext)
                waiters.next().trySuccess(newMac)
        }
    }

    private def makeArpRequest(srcMac: MAC, srcIP: IPv4Addr, dstIP: IPv4Addr): Ethernet = {
        val arp = new ARP()
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET)
        arp.setProtocolType(ARP.PROTO_TYPE_IP)
        arp.setHardwareAddressLength(6.asInstanceOf[Byte])
        arp.setProtocolAddressLength(4.asInstanceOf[Byte])
        arp.setOpCode(ARP.OP_REQUEST)
        arp.setSenderHardwareAddress(srcMac)
        arp.setTargetHardwareAddress(MAC.fromString("00:00:00:00:00:00"))
        arp.setSenderProtocolAddress(IPv4Addr.intToBytes(srcIP.toInt))
        arp.setTargetProtocolAddress(IPv4Addr.intToBytes(dstIP.toInt))
        val pkt: Ethernet = new Ethernet
        pkt.setPayload(arp)
        pkt.setSourceMACAddress(srcMac)
        pkt.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"))
        pkt.setEtherType(ARP.ETHERTYPE)
    }

    class ArpLoop(val ip: IPv4Addr, val port: RouterPort) {
        private val timeout = clock.time + config.arptable.timeout

        /*
         * jitter: multiplier for the configured arp retry interval.
         *
         * Base jitter is a random number between 0.75 and 1.25. Each
         * ARP retry increments it by 0.1.
         */
        private val baseJitter = random.nextDouble() * 0.5 + 0.75
        private var retries = -1

        private def jitter: Double = baseJitter + retries * .1

        var _nextTry: Long = 0L
        def nextTry = _nextTry

        def tick() {
            retries += 1
            _nextTry = (config.arptable.retryInterval * jitter).toLong
        }

        tick()

        def shouldRetry: Boolean = clock.time > timeout
    }
}
