/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.simulation

import java.util.concurrent.TimeUnit

import scala.collection.mutable
import scala.compat.Platform
import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import com.typesafe.scalalogging.Logger

import org.midonet.cluster.client.{ArpCache, RouterPort}
import org.midonet.midolman.PacketsEntryPoint
import org.midonet.midolman.{Ready, NotYet, Urgent}
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.state.ArpCacheEntry
import org.midonet.packets.{ARP, Ethernet, IPv4Addr, MAC}
import org.midonet.util.functors.Callback3

/* The ArpTable is called from the Coordinators' actors and
 * processes and schedules ARPs. */
trait ArpTable {
    def get(ip: IPv4Addr, port: RouterPort, expiry: Long)
           (implicit ec: ExecutionContext,
                     pktContext: PacketContext): Urgent[MAC]
    def set(ip: IPv4Addr, mac: MAC)(implicit ec: ExecutionContext)
    def setAndGet(ip: IPv4Addr, mac: MAC, port: RouterPort, expiry: Long)
                 (implicit ec: ExecutionContext): Future[MAC]
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
                  (implicit system: ActorSystem) extends ArpTable {
    private val log = Logger(org.slf4j.LoggerFactory.getLogger(
        "org.midonet.devices.arp-table.arp-table-" + arpCache.getRouterId))
    private val ARP_RETRY_MILLIS = cfg.getArpRetryIntervalSeconds * 1000
    private val ARP_TIMEOUT_MILLIS = cfg.getArpTimeoutSeconds * 1000
    private val ARP_STALE_MILLIS = cfg.getArpStaleSeconds * 1000
    private val ARP_EXPIRATION_MILLIS = cfg.getArpExpirationSeconds * 1000
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
     * @param expiry
     * @param onExpire If the promise is expired, onExpire will be executed
     *                 with the promise as an argument.
     * @tparam T
     * @return
     */
    private def promiseOnExpire[T](promise: Promise[T], expiry: Long,
                                   onExpire: (Promise[T]) => Unit)
                                  (implicit ec: ExecutionContext): Promise[T] = {
        val now = Platform.currentTime
        if (now >= expiry) {
            if (promise tryFailure new TimeoutException()) {
                system.dispatcher.execute(new Runnable {
                    def run() = onExpire(promise)
                })
            }
            return promise
        }
        val when = Duration.create(expiry - now, TimeUnit.MILLISECONDS)
        val exp = system.scheduler.scheduleOnce(when) {
            if (promise tryFailure new TimeoutException())
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

    def waitForArpEntry(ip: IPv4Addr, expiry: Long)
                       (implicit ec: ExecutionContext) : Promise[MAC] = {
        val p = promise[MAC]()
        arpWaiters.addBinding(ip, p)
        promiseOnExpire[MAC](p, expiry, p => removeArpWaiter(ip, p))
    }

    def get(ip: IPv4Addr, port: RouterPort, expiry: Long)
           (implicit ec: ExecutionContext,
                     pktContext: PacketContext): Urgent[MAC] = {
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
        val now = Platform.currentTime

        if (arpCacheEntry == null
            || (arpCacheEntry.stale < now &&
                arpCacheEntry.lastArp+ARP_RETRY_MILLIS < now)
            || arpCacheEntry.macAddr == null) {
            arpForAddress(ip, arpCacheEntry, port)
        }

        // macPromise may complete with a Left(TimeoutException). Use
        // fallbackTo so that exceptions don't escape the ArpTable class.
        if (arpCacheEntry != null && arpCacheEntry.macAddr != null &&
            arpCacheEntry.expiry >= Platform.currentTime) {
            Ready(arpCacheEntry.macAddr)
        } else {
            pktContext.log.debug("MAC for IP {} unknown, suspending during ARP", ip)
            NotYet(waitForArpEntry(ip, expiry).future)
        }
    }

    def setAndGet(ip: IPv4Addr, mac: MAC, port: RouterPort, expiry: Long)
                  (implicit ec: ExecutionContext): Future[MAC] = {
        val macFuture = waitForArpEntry(ip, expiry).future

        set(ip, mac)
        val entry = arpCache.get(ip)

        if (entry != null && entry.macAddr != null &&
                entry.expiry >= Platform.currentTime) {
            Future.successful(entry.macAddr)
        } else {
            macFuture
        }
    }

    def set(ip: IPv4Addr, mac: MAC) (implicit ec: ExecutionContext) {
        val now = Platform.currentTime
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
    private def expireCacheEntry(ip: IPv4Addr)
                                (implicit ec: ExecutionContext) {
        val entry = arpCache.get(ip)
        if (entry != null) {
            if (entry.expiry <= Platform.currentTime)
                arpCache.remove(ip)
        }
        // TODO(pino): else retry the removal?
    }

    private def arpForAddress(ip: IPv4Addr, entry: ArpCacheEntry,
                              port: RouterPort)
                             (implicit ec: ExecutionContext,
                              pktContext: PacketContext) {
        def retryLoopBottomHalf(cacheEntry: ArpCacheEntry, arp: Ethernet,
                                previous: Long) {
            val now = Platform.currentTime
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
            PacketsEntryPoint !
                    EmitGeneratedPacket(port.id, arp,
                        if (pktContext != null) pktContext.flowCookie else None)
            // we don't retry for stale entries.
            if (cacheEntry.macAddr == null) {
                waitForArpEntry(ip, now + ARP_RETRY_MILLIS).future onFailure {
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

        val now = Platform.currentTime
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
