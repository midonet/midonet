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

import java.lang.{Long => JLong}
import java.util
import java.util.{ArrayDeque, Comparator, PriorityQueue, UUID}
import scala.concurrent.{Promise, Future}
import scala.util.Random

import com.google.common.collect.ArrayListMultimap
import com.typesafe.scalalogging.Logger
import org.jctools.queues.SpscLinkedQueue
import org.slf4j.LoggerFactory

import org.midonet.cluster.client.ArpCache
import org.midonet.midolman.NotYetException
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.flows.InvalidationSource
import org.midonet.midolman.simulation._
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPacket
import org.midonet.midolman.topology.devices.RouterPort
import org.midonet.packets.{Ethernet, IPv4Addr, MAC}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.UnixClock
import org.midonet.util.functors.Callback3

object ArpRequestBroker {
    case class MacChange(ip: IPv4Addr, oldMac: MAC, newMac: MAC)

    /*
     * Staleness jitter: This multiplier is applied to the configured
     * stale time to decide when to try and refresh an ARP table entry.
     *
     * If staleness happens at 30 minutes jitter will be somewhere between 0
     * and 6 minutes.
     */
    val STALE_JITTER: Double = 0.2

    /*
     * Retry jitter: multiplier for the configured arp retry interval.
     *
     * Base jitter is a random number between 0.75 and 1.25. Each
     * ARP retry increments it by 0.1.
     */
    val RETRY_MIN_BASE_JITTER: Double = 0.75
    val RETRY_MAX_BASE_JITTER: Double = 1.25
    val RETRY_JITTER_GAP: Double = RETRY_MAX_BASE_JITTER - RETRY_MIN_BASE_JITTER
    val RETRY_JITTER_INCREMENT: Double = 0.1
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
 */
class ArpRequestBroker(emitter: PacketEmitter,
                       config: MidolmanConfig,
                       invalidator: InvalidationSource,
                       clock: UnixClock = UnixClock()) {
    val log = Logger(LoggerFactory.getLogger(s"org.midonet.devices.router.arptable"))
    private val brokers = new util.HashMap[UUID, SingleRouterArpRequestBroker]()

    def numRouters = brokers.size

    def broker(router: Router): SingleRouterArpRequestBroker = {
        brokers.get(router.id) match {
            case null =>
                log.info(s"Building new arp request broker for router ${router.id}")
                val broker = new SingleRouterArpRequestBroker(router.id,
                        router.arpCache, emitter, config, invalidator, clock)
                brokers.put(router.id, broker)
                broker
            case broker => broker
        }
    }

    /*
     * Queries the ARP table, throwing a NotYetException if the MAC is unknown.
     *
     * It will initiate a new ARP request loop if the MAC is unknown or stale
     * and the broker has not already initiated an ARP request loop for this IP
     * address.
     */
    @throws(classOf[NotYetException])
    def get(ip: IPv4Addr, port: RouterPort, router: Router): MAC = {
        broker(router).get(ip, port)
    }

    /*
     * Writes an entry to the ARP table. This will eventually complete the
     * promises that were waiting on this entry in this an other threads/agents
     * when the write has been committed to storage.
     */
    def set(ip: IPv4Addr, mac: MAC, router: Router) = {
        broker(router).set(ip, mac)
    }

    /*
     * Writes an entry to the ARP table and gets its value as a future.
     *
     * The returned future will only be completed when the write (if the MAC
     * was unknown up to this call) has been committed to storage.
     */
    def setAndGet(ip: IPv4Addr, mac: MAC, port: RouterPort,
            router: Router): Future[MAC] = {
        broker(router).setAndGet(ip, mac, port)
    }

    def process(): Unit = {
        val tables = brokers.values.iterator()
        while (tables.hasNext) {
            tables.next().process()
        }
    }

    def shouldProcess(): Boolean = {
        val tables = brokers.values.iterator()
        var should = false
        while (tables.hasNext && !should) {
            val next = tables.next()
            should = should || next.shouldProcess()
        }
        should
    }
}

class SingleRouterArpRequestBroker(id: UUID,
                                   arpCache: ArpCache,
                                   emitter: PacketEmitter,
                                   config: MidolmanConfig,
                                   invalidator: InvalidationSource,
                                   clock: UnixClock = UnixClock()) {
    import ArpRequestBroker._

    val log = Logger(LoggerFactory.getLogger(s"org.midonet.devices.router.arptable-$id"))

    private val random = new Random()

    private val LOOP_COMPARE = new Comparator[ArpLoop] {
        override def compare(a: ArpLoop, b: ArpLoop) = JLong.compare(a.nextTry, b.nextTry)
    }

    /*
     * Set of IPv4 addresses we are currently ARP'ing for. Used as a guard
     * against the initiation of multiple ARP request loops.
     */
    private val arpLoops = new util.HashSet[IPv4Addr]()

    /*
     * Priority queue of currently active ARP request loops, ordered by
     * time to their next retry. It will be checked periodically to emit
     * any pending ARP request or expire any timed out loops.
     */
    private val arpLoopQ = new PriorityQueue[ArpLoop](32, LOOP_COMPARE)
    /*
     * Guarantee arpLoopQ will never be empty
     */
    arpLoopQ.add(ImmortalLoop)

    /*
     * Queue of ARP entry expirations. It contains in FIFO order, all the
     * arp entries created by this ArpRequestBroker. In other words, entries
     * for which set() was called on this specific instance. This instance is
     * responsible for the expiration of the entries it has itself created. It
     * does not need to worry about other entries because they are ephemeral
     * and guaranteed to disappear if other nodes fail.
     */
    private val expiryQ = new ArrayDeque[(Long, IPv4Addr)]()

    /*
     * Unfulfilled MAC promises for all packets that had to be suspended because
     * of a miss in the ARP table.
     */
    private val arpWaiters = ArrayListMultimap.create[IPv4Addr, Promise[MAC]]()

    /*
     * Privately managed back-channel through which this class hears of newly
     * learned MACs. This class will install a callback in the underlying
     * ArpCache's reactor to write events to this concurrent queue. It will later
     * process these events sequentially when process() is invoked.
     */
    private val macsDiscovered = new SpscLinkedQueue[MacChange]()

    /**
     * Notify this ArpRequestBroker that the ArpTable has discovered a new
     * MAC - IP address association. May be called from any thread.
     */
    arpCache.notify(new Callback3[IPv4Addr, MAC, MAC] {
        override def call(ip: IPv4Addr, oldMac: MAC, newMac: MAC): Unit = {
            macsDiscovered.add(MacChange(ip, oldMac, newMac))
        }
    })

    private val stalenessJitter: Long = {
        (config.arptable.stale * STALE_JITTER * random.nextDouble()).toLong
    }

    private def shouldArp(cacheEntry: ArpCacheEntry): Boolean = {
        (cacheEntry eq null) || (cacheEntry.macAddr eq null) ||
            (clock.time > (cacheEntry.stale + stalenessJitter))
    }

    private def upToDate(entry: ArpCacheEntry): Boolean = !shouldArp(entry)

    /*
     * Checks whether this broker is actually managing any work right now.
     * Will declare itself idle if there are no ongoing ARP request loops,
     * there are no entries in the expiry queue and there are no MAC promises
     * awaiting fulfillment.
     */
    def isIdle: Boolean = arpLoops.isEmpty && expiryQ.isEmpty && arpWaiters.isEmpty

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

    @volatile
    private var nextEvent = Long.MaxValue

    private def updateNextEvent() = {
        nextEvent = arpLoopQ.peek().nextTry
    }

    def shouldProcess(): Boolean = (!macsDiscovered.isEmpty) || (clock.time >= nextEvent)

    private def arpLoopIsReady: Boolean = arpLoopQ.peek().nextTry < clock.time

    /*
     * Queries the ARP table, returning a MAC or throwing a NotYetException
     * if the MAC is unknownn.
     */
    @throws(classOf[NotYetException])
    def get(ip: IPv4Addr, port: RouterPort): MAC = {

        val cacheEntry = arpCache.get(ip)

        if (shouldArp(cacheEntry)) {
            arpForAddress(ip, port)
            if ((cacheEntry ne null) && (cacheEntry.macAddr ne null))
                cacheEntry.macAddr
            else
                throw new NotYetException(waitForArpEntry(ip), s"MAC for IP $ip unknown, suspending during ARP")
        } else {
            cacheEntry.macAddr
        }
    }

    private def waitForArpEntry(ip: IPv4Addr): Future[MAC] = {
        val promise = Promise[MAC]()
        arpWaiters.put(ip, promise)
        promise.future
    }

    private def arpForAddress(ip: IPv4Addr, port: RouterPort): Unit = {
        if (arpLoops.contains(ip))
            return

        val loop = new ArpLoop(ip, port)

        val arp = makeArpRequest(port.portMac, port.portAddr.getAddress, ip)
        emitter.schedule(GeneratedPacket(port.id, arp))

        arpLoops.add(ip)
        arpLoopQ.add(loop)
        updateNextEvent()
    }

    /**
     * Writes a learned MAC to the ARP table. It's a NO-OP unless the entry was
     * unknown or stale.
     */
    def set(ip: IPv4Addr, mac: MAC): Unit = {
        val entry = arpCache.get(ip)

        if (upToDate(entry) && mac == entry.macAddr) {
            log.debug("Skipping write to ArpCache because a non-stale " +
                "entry for {} with the same value ({}) exists.", ip, mac)
        } else {
            log.debug("Got address for {}: {}", ip, mac)
            val now = clock.time
            val entry = new ArpCacheEntry(mac, now + config.arptable.expiration,
                                               now + config.arptable.stale, 0)
            arpCache.add(ip, entry)
            expiryQ.add((entry.expiry, ip))
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
        while ((!expiryQ.isEmpty) && (expiryQ.peek()._1 <= clock.time)) {
            val (_, ip) = expiryQ.poll()
            val entry = arpCache.get(ip)

            /* This is racy because the remove() op is not CAS.
             * This means we could delete an entry written just now by another
             * node that refreshed the arp cache entry. The result would be
             * that MidoNet would have to ARP again, and in this case the race
             * would not be possible because there would be no expirer to
             * race with the writer. */
            if (entry != null && entry.expiry <= clock.time)
                arpCache.remove(ip)
        }
    }

    private def processArpLoops() {
        val now = clock.time
        while (arpLoopIsReady) {
            val loop = arpLoopQ.poll()
            val entry = arpCache.get(loop.ip)

            if (upToDate(entry)) {
                arpLoops.remove(loop.ip)
                keepPromises(loop.ip, entry.macAddr)
            } else if (loop.timedOut) {
                arpLoops.remove(loop.ip)
                breakPromises(loop.ip)
            } else {
                val arp = makeArpRequest(loop.port.portMac,
                                         loop.port.portAddr.getAddress,
                                         loop.ip)
                emitter.schedule(GeneratedPacket(loop.port.id, arp))
                loop.tick()
                arpLoopQ.add(loop)
            }
        }
        updateNextEvent()
    }

    private def breakPromises(ip: IPv4Addr) {
        val waiters = arpWaiters.removeAll(ip).iterator()
        while (waiters.hasNext)
            waiters.next().tryFailure(ArpTimeoutException(id, ip))
    }

    private def keepPromises(ip: IPv4Addr, mac: MAC) {
        val waiters = arpWaiters.removeAll(ip).iterator()
        while (waiters.hasNext)
            waiters.next().trySuccess(mac)
    }

    private def processNewMacs(): Unit = while (!macsDiscovered.isEmpty) {
        val MacChange(ip, oldMac, newMac) = macsDiscovered.poll()
        if (newMac ne null) {
            if ((oldMac ne null) && (newMac != oldMac)) {
                log.debug("Invalidating flows for {} in router {}", ip, id)
                invalidator.scheduleInvalidationFor(FlowTagger.tagForArpEntry(id, ip))
            }
            keepPromises(ip, newMac)
        }
    }

    private def makeArpRequest(srcMac: MAC, srcIp: IPv4Addr, dstIp: IPv4Addr): Ethernet = {
        import org.midonet.packets.util.PacketBuilder._
        log.debug(s"Sending ARP request for $dstIp")

        { eth addr srcMac -> eth_bcast } <<
            { arp.req mac srcMac -> eth_zero ip srcIp --> dstIp}
    }

    class ArpLoop(val ip: IPv4Addr, val port: RouterPort) {
        private val timeout = clock.time + config.arptable.timeout

        private val baseJitter = random.nextDouble() * RETRY_JITTER_GAP + RETRY_MIN_BASE_JITTER
        private var retries = -1

        private def jitter: Double = baseJitter + retries * RETRY_JITTER_INCREMENT

        var _nextTry: Long = 0L
        def nextTry = _nextTry

        def tick() {
            retries += 1
            _nextTry = clock.time + (config.arptable.retryInterval * jitter).toLong
        }

        tick()

        def timedOut: Boolean = clock.time >= timeout
    }

    object ImmortalLoop extends ArpLoop(IPv4Addr.fromString("255.255.255.255" ), null) {
        override val nextTry = Long.MaxValue
        override def tick() {}
        override def timedOut = false
    }
}
