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

import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}
import scala.util.Random

import com.google.common.collect.ArrayListMultimap

import org.jctools.queues.SpscGrowableArrayQueue

import org.midonet.cluster.data.storage.model.ArpEntry
import org.midonet.midolman.{NotYetException, SimulationBackChannel}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.PacketWorkflow.GeneratedLogicalPacket
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.simulation._
import org.midonet.packets.{Ethernet, IPv4Addr, MAC}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.UnixClock
import org.midonet.util.functors.makeAction1

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
class ArpRequestBroker(config: MidolmanConfig,
                       backChannel: SimulationBackChannel,
                       clock: UnixClock = UnixClock())
    extends MidolmanLogging {

    override def logSource = "org.midonet.devices.router.arptable"

    private val brokers = new util.HashMap[UUID, SingleRouterArpRequestBroker]()

    def numRouters = brokers.size

    def broker(router: Router): SingleRouterArpRequestBroker = {
        brokers.get(router.id) match {
            case null =>
                log.debug(s"Building new ARP request broker for router ${router.id}")
                val broker = new SingleRouterArpRequestBroker(router.id,
                        router.arpCache, config, backChannel, clock)
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
    def get(ip: IPv4Addr, port: RouterPort, router: Router, cookie: Long): MAC = {
        broker(router).get(ip, port, cookie)
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
            router: Router, cookie: Long): Future[MAC] = {
        broker(router).setAndGet(ip, mac, port, cookie)
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
                                   config: MidolmanConfig,
                                   backChannel: SimulationBackChannel,
                                   clock: UnixClock = UnixClock())
    extends MidolmanLogging {

    import ArpRequestBroker._

    override def logSource = "org.midonet.devices.router.arptable"
    override def logMark = s"router:$id"

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
    private val macsDiscovered = new SpscGrowableArrayQueue[MacChange](256, 1 << 30)

    /**
     * Notify this ArpRequestBroker when the ArpTable has discovered a new
     * MAC - IP address association. May be called from any thread.
     */
    arpCache.observable.subscribe(makeAction1[ArpCacheUpdate] { u =>
        macsDiscovered.add(MacChange(u.ipAddr, u.oldMac, u.newMac))
    })

    private val stalenessJitter: Long = {
        (config.arptable.stale * STALE_JITTER * random.nextDouble()).toLong
    }

    private def shouldArp(cacheEntry: ArpEntry): Boolean = {
        (cacheEntry eq null) || (cacheEntry.mac eq null) ||
            (clock.time > (cacheEntry.stale + stalenessJitter))
    }

    private def upToDate(entry: ArpEntry): Boolean = !shouldArp(entry)

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
    def get(ip: IPv4Addr, port: RouterPort, cookie: Long): MAC = {

        val cacheEntry = arpCache.get(ip)

        if (shouldArp(cacheEntry)) {
            arpForAddress(ip, port, cookie)
            if ((cacheEntry ne null) && (cacheEntry.mac ne null))
                cacheEntry.mac
            else
                throw new NotYetException(waitForArpEntry(ip), s"MAC for IP $ip unknown, suspending during ARP")
        } else {
            cacheEntry.mac
        }
    }

    private def waitForArpEntry(ip: IPv4Addr): Future[MAC] = {
        val promise = Promise[MAC]()
        arpWaiters.put(ip, promise)
        promise.future
    }

    private def arpForAddress(ip: IPv4Addr, port: RouterPort, cookie: Long): Unit = {
        if (arpLoops.contains(ip))
            return
        if (port.portAddress4 eq null)
            return

        val loop = new ArpLoop(ip, port, cookie)

        val arp = makeArpRequest(port.portMac, port.portAddress4.getAddress, ip)
        backChannel.tell(GeneratedLogicalPacket(port.id, arp, cookie))

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

        if (upToDate(entry) && mac == entry.mac) {
            log.debug("Skipping write to ArpCache because a non-stale " +
                "entry for {} with the same value ({}) exists.", ip, mac)
        } else {
            log.debug("Got address for {}: {}", ip, mac)
            val now = clock.time
            val entry = new ArpEntry(mac, now + config.arptable.expiration,
                                               now + config.arptable.stale, 0)
            arpCache.add(ip, entry)
            expiryQ.add((entry.expiry, ip))
        }
    }

    def setAndGet(ip: IPv4Addr, mac: MAC, port: RouterPort, cookie: Long): Future[MAC] = {
        set(ip, mac)
        val entry = arpCache.get(ip)

        if ((entry ne null) && (entry.mac ne null))
            Future.successful(entry.mac)
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
            if ((entry ne null) && (entry.expiry <= clock.time))
                arpCache.remove(ip)
        }
    }

    private def processArpLoops() {
        while (arpLoopIsReady) {
            val loop = arpLoopQ.poll()
            val entry = arpCache.get(loop.ip)

            if (upToDate(entry)) {
                arpLoops.remove(loop.ip)
                keepPromises(loop.ip, entry.mac)
            } else if (loop.timedOut) {
                arpLoops.remove(loop.ip)
                breakPromises(loop.ip)
            } else {
                val arp = makeArpRequest(loop.port.portMac,
                                         loop.port.portAddress4.getAddress,
                                         loop.ip)
                backChannel.tell(
                    GeneratedLogicalPacket(loop.port.id, arp, loop.cookie))
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

    @tailrec
    private def processNewMacs(): Unit =
        macsDiscovered.poll() match {
            case null =>
            case MacChange(ip, oldMac, newMac) if newMac ne null =>
                log.debug("Got address for {}: {}", ip, newMac)
                if ((oldMac ne null) && (newMac != oldMac)) {
                    log.debug("Invalidating flows for {} in router {}", ip, id)
                    backChannel.tell(FlowTagger.tagForArpEntry(id, ip))
                }
                keepPromises(ip, newMac)
                processNewMacs()
            case _ =>
                processNewMacs()
    }

    private def makeArpRequest(srcMac: MAC, srcIp: IPv4Addr, dstIp: IPv4Addr): Ethernet = {
        import org.midonet.packets.util.PacketBuilder._
        log.debug(s"Sending ARP request for $dstIp")

        { eth addr srcMac -> eth_bcast } <<
            { arp.req mac srcMac -> eth_zero ip srcIp --> dstIp}
    }

    class ArpLoop(val ip: IPv4Addr, val port: RouterPort, val cookie: Long) {
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

    object ImmortalLoop extends ArpLoop(IPv4Addr.fromString("255.255.255.255" ), null, -1) {
        override val nextTry = Long.MaxValue
        override def tick() {}
        override def timedOut = false
    }
}
