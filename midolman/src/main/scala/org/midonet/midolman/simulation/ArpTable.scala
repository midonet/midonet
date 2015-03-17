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
package org.midonet.midolman.simulation

import java.util.concurrent.TimeUnit

import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration.Duration

import akka.actor.ActorSystem
import com.typesafe.scalalogging.Logger

import org.midonet.cluster.client.ArpCache
import org.midonet.midolman._
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.simulation.PacketEmitter.GeneratedPacket
import org.midonet.midolman.state.ArpCacheEntry
import org.midonet.midolman.topology.devices.RouterPort
import org.midonet.packets.{ARP, Ethernet, IPv4Addr, MAC}
import org.midonet.util.UnixClock
import org.midonet.util.functors.Callback3

/* The ArpTable is called from the Coordinators' actors and
 * processes and schedules ARPs. */
trait ArpTable {
    def get(ip: IPv4Addr, port: RouterPort)
           (implicit pktContext: PacketContext): MAC
    def set(ip: IPv4Addr, mac: MAC)
    def setAndGet(ip: IPv4Addr, mac: MAC, port: RouterPort): Future[MAC]
    def start()
    def stop()
}

/* MultiMap isn't thread-safe and the SynchronizedMap trait does not
 * help either. See: https://issues.scala-lang.org/browse/SI-6087
 * and/or the MultiMap and SynchronizedMap source files.
 */
trait SynchronizedMultiMap[A, B] extends mutable.MultiMap[A, B] with
        mutable.SynchronizedMap[A, mutable.Set[B]] {
    override def addBinding(k: A, v: B): this.type = synchronized[this.type] {
        super.addBinding(k, v)
    }

    override def removeBinding(k: A, v: B): this.type = synchronized[this.type] {
        super.removeBinding(k, v)
    }
}

class ArpTableImpl(val arpCache: ArpCache, cfg: MidolmanConfig,
                   val observer: (IPv4Addr, MAC, MAC) => Unit)
                  (implicit system: ActorSystem,
                            ec: ExecutionContext) extends ArpTable {

    val clock = UnixClock.get

    private val log = Logger(org.slf4j.LoggerFactory.getLogger(
        "org.midonet.devices.arp-table.arp-table-" + arpCache.getRouterId))
    private val ARP_RETRY_MILLIS = cfg.arptable.retryInterval * 1000
    private val ARP_TIMEOUT_MILLIS = cfg.arptable.timeout * 1000
    private val ARP_STALE_MILLIS = cfg.arptable.stale * 1000
    private val ARP_EXPIRATION_MILLIS = cfg.arptable.expiration * 1000
    private val arpWaiters = new mutable.HashMap[IPv4Addr,
                                            mutable.Set[Promise[MAC]]] with
            SynchronizedMultiMap[IPv4Addr, Promise[MAC]]
    private var arpCacheCallback: Callback3[IPv4Addr, MAC, MAC] = null

    override def start() {
        arpCacheCallback = new Callback3[IPv4Addr, MAC, MAC] {
            def call(ip: IPv4Addr, oldMac: MAC, newMac: MAC) {
                if (newMac == null)
                    return
                log.debug("invalidating flows for {}", ip)

                /* Do not invalidate flows the first time that a mac is set.
                 * That could render the simulations that triggered the ARP
                 * request invalid. */
                if (oldMac != null)
                    observer(ip, oldMac, newMac)
                arpWaiters.remove(ip) match {
                    case Some(waiters)  =>
                        log.debug(s"notify ${waiters.size} waiters for $ip at $newMac")
                        waiters map { _ success newMac }
                    case _ =>
                }
            }
        }
        arpCache.notify(arpCacheCallback)
    }

    override def stop() {
        if (arpCacheCallback != null) {
            arpCache.unsubscribe(arpCacheCallback)
            arpCacheCallback = null
        }
    }

    /**
     * Schedule promise to fail with a TimeoutException at a given time.
     *
     * @param promise
     * @param onExpire If the promise is expired, onExpire will be executed
     *                 with the promise as an argument.
     * @tparam T
     * @return
     */
    private def promiseOnExpire[T](promise: Promise[T],
                                   timeout: Long,
                                   onExpire: (Promise[T]) => Unit,
                                   expiration: () => ArpTimeoutException): Promise[T] = {
        val when = Duration.create(timeout, TimeUnit.MILLISECONDS)
        val exp = system.scheduler.scheduleOnce(when) {
            if (promise tryFailure expiration())
                onExpire(promise)
        }
        promise.future onComplete {
            case _ =>
                if (!exp.isCancelled)
                    exp.cancel()
        }
        promise
    }

    private def removeArpWaiter(ip: IPv4Addr, promise: Promise[MAC]) {
        arpWaiters.removeBinding(ip, promise)
    }

    def waitForArpEntry(ip: IPv4Addr, timeout: Long): Promise[MAC] = {
        val p = Promise[MAC]()
        arpWaiters.addBinding(ip, p)
        promiseOnExpire[MAC](p, timeout, removeArpWaiter(ip, _),
                             () => ArpTimeoutException(arpCache.getRouterId, ip))
    }

    def get(ip: IPv4Addr, port: RouterPort)
           (implicit pktContext: PacketContext): MAC = {
        pktContext.log.debug("Resolving MAC for {}", ip)
        /*
         * We used to waitForArpEntry() before requesting the ArpCacheEntry
         * before we had pauseless simulations to avoid a race between the
         * get() asking for the cached entry, the get receiving it as empty,
         * then a reply arriving and being notified just too late missig the
         * notification.
         *
         * Since simulations will run now without stop, this race becomes
         * irrelevant, if we don't have the data right now, the simulation must
         * be postponed (on a future that waits for the mac's notification).
         */
        val arpCacheEntry = arpCache.get(ip)
        val now = clock.time

        if (arpCacheEntry == null
            || (arpCacheEntry.stale < now &&
                arpCacheEntry.lastArp+ARP_RETRY_MILLIS < now)
            || arpCacheEntry.macAddr == null) {
            arpForAddress(ip, arpCacheEntry, port)
        }

        if (arpCacheEntry != null && arpCacheEntry.macAddr != null &&
            arpCacheEntry.expiry >= clock.time) {
            arpCacheEntry.macAddr
        } else {
            throw new NotYetException(waitForArpEntry(ip, ARP_TIMEOUT_MILLIS).future,
                                      s"MAC for IP $ip unknown, suspending during ARP")
        }
    }

    def setAndGet(ip: IPv4Addr, mac: MAC, port: RouterPort): Future[MAC] = {
        val macFuture = waitForArpEntry(ip, ARP_TIMEOUT_MILLIS).future

        set(ip, mac)
        val entry = arpCache.get(ip)

        if (entry != null && entry.macAddr != null &&
                entry.expiry >= clock.time) {
            Future.successful(entry.macAddr)
        } else {
            macFuture
        }
    }

    def set(ip: IPv4Addr, mac: MAC) {
        val now = clock.time
        val entry = arpCache.get(ip)
        if (entry != null &&
            entry.macAddr != null && entry.stale > now &&
            entry.macAddr == mac) {
            log.debug("Skipping write to ArpCache because a non-stale " +
                "entry for {} with the same value ({}) exists.", ip, mac)
        } else {
            log.debug("Got address for {}: {}", ip, mac)
            val entry = new ArpCacheEntry(mac, now + ARP_EXPIRATION_MILLIS,
                now + ARP_STALE_MILLIS, 0)
            arpCache.add(ip, entry)
            val when = Duration.create(ARP_EXPIRATION_MILLIS,
                TimeUnit.MILLISECONDS)
            system.scheduler.scheduleOnce(when){ expireCacheEntry(ip) }
        }
    }

    private def makeArpRequest(srcMac: MAC, srcIP: IPv4Addr, dstIP: IPv4Addr):
    Ethernet = {
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

    // XXX cancel scheduled expires when an entry is refreshed?
    private def expireCacheEntry(ip: IPv4Addr) {
        val entry = arpCache.get(ip)
        if (entry != null) {
            if (entry.expiry <= clock.time)
                arpCache.remove(ip)
        }
        // TODO(pino): else retry the removal?
    }

    private def arpForAddress(ip: IPv4Addr, entry: ArpCacheEntry,
                              port: RouterPort)
                             (implicit pktContext: PacketContext) {
        val packetEmitter = pktContext.packetEmitter
        def retryLoopBottomHalf(cacheEntry: ArpCacheEntry, arp: Ethernet,
                                previous: Long) {
            val now = clock.time
            // expired, no retries left.
            if (cacheEntry == null || cacheEntry.expiry <= now)
                return
            // another node took over, give up. Waiters will be notified.
            if (previous > 0 && cacheEntry.lastArp != previous)
                return
            // now up to date, entry was updated while the top half
            // of the retry loop waited for the arp cache entry, waiters
            // will be notified naturally, do nothing.
            if (cacheEntry.macAddr != null && cacheEntry.stale > now)
                return

            cacheEntry.lastArp = now
            arpCache.add(ip, cacheEntry)
            log.debug("generateArpRequest: sending {}", arp)

            // If the queue of pending generated packets is full, we'll
            // retry later.
            packetEmitter.schedule(GeneratedPacket(port.id, arp))

            // we don't retry for stale entries.
            if (cacheEntry.macAddr == null) {
                waitForArpEntry(ip, ARP_RETRY_MILLIS).future onFailure {
                    case ex: TimeoutException =>
                        retryLoopTopHalf(arp, now)
                    case ex =>
                        log.error("waitForArpEntry unexpected exception {}", ex)
                }
            }
        }

        def retryLoopTopHalf(arp: Ethernet, previous: Long) {
            val entry = arpCache.get(ip)
            if (entry == null)
                retryLoopBottomHalf(null, arp, previous)
            else
                retryLoopBottomHalf(entry.clone(), arp, previous)
        }

        val now = clock.time
        // this is open to races with other nodes
        // for now we take over sending ARPs if no ARP has been sent
        // in RETRY_INTERVAL * 2. And, obviously, the MAC is null or stale.
        //
        // But when that happens (for some reason a node stopped following
        // through with the retries) other nodes will race to take over.
        if (entry != null && entry.lastArp + ARP_RETRY_MILLIS*2 > now)
            return

        // ArpCacheEntries that have expired should be removed automatically.
        // However, we had a bug (#551) where old entries were not cleaned up
        // properly in the underlying map. That confused our algorithm because
        // we don't send ARP requests when the entry is expired (we interpret
        // that to mean that there are no more retries left). The workaround
        // is to treat an expired entry the same as a null/missing entry.
        var newEntry: ArpCacheEntry = null
        if (entry == null || now > entry.expiry) {
            newEntry = new ArpCacheEntry(null, now + ARP_TIMEOUT_MILLIS,
                                         now + ARP_RETRY_MILLIS, now)
            val when = Duration.create(ARP_TIMEOUT_MILLIS,
                                       TimeUnit.MILLISECONDS)
            system.scheduler.scheduleOnce(when){ expireCacheEntry(ip) }
        } else {
            // XXX race: when this key is re-added to the map someone else
            // may have written to it.
            newEntry = entry.clone()
        }

        val pkt = makeArpRequest(port.portMac, port.portAddr.getAddress, ip)
        retryLoopBottomHalf(newEntry, pkt, 0)
    }
}
