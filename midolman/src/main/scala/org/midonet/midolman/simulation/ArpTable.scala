/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.simulation

import collection.mutable
import compat.Platform
import akka.actor.{ActorContext, ActorSystem}
import akka.dispatch.{ExecutionContext, Future, Promise}
import akka.util.Duration
import java.util.concurrent.{TimeUnit, TimeoutException}

import org.midonet.cluster.client.{ArpCache, RouterPort}
import org.midonet.midolman.DeduplicationActor
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.state.ArpCacheEntry
import org.midonet.packets.{ARP, Ethernet, IPv4, IPv4Addr, MAC}
import org.midonet.util.functors.{Callback2, Callback1}


/* The ArpTable is called from the Coordinators' actors and
 * processes and schedules ARPs. */
trait ArpTable {
    def get(ip: IPv4Addr, port: RouterPort, expiry: Long)
          (implicit ec: ExecutionContext, actorSystem: ActorSystem,
           pktContext: PacketContext): Future[MAC]
    def set(ip: IPv4Addr, mac: MAC) (implicit actorSystem: ActorSystem)
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
                   val observer: (IPv4Addr, MAC) => Unit)
                  (implicit context: ActorContext) extends ArpTable {
    private val log =
          LoggerFactory.getActorSystemThreadLog(this.getClass)(context.system.eventStream)
    private val ARP_RETRY_MILLIS = cfg.getArpRetryIntervalSeconds * 1000
    private val ARP_TIMEOUT_MILLIS = cfg.getArpTimeoutSeconds * 1000
    private val ARP_STALE_MILLIS = cfg.getArpStaleSeconds * 1000
    private val ARP_EXPIRATION_MILLIS = cfg.getArpExpirationSeconds * 1000
    private val arpWaiters = new mutable.HashMap[IPv4Addr,
                                             mutable.Set[Promise[MAC]]] with
            SynchronizedMultiMap[IPv4Addr, Promise[MAC]]
    private var arpCacheCallback: Callback2[IPv4Addr, MAC] = null

    override def start() {
        arpCacheCallback = new Callback2[IPv4Addr, MAC] {
            def call(ip: IPv4Addr, mac: MAC) {
                if (mac == null)
                    return
                log.debug("invalidating flows for {}", ip)
                observer(ip, mac)
                arpWaiters.remove(ip) match {
                    case Some(waiters)  =>
                        log.debug("ArpCache.notify cb, fwd to {} waiters -- {}",
                                  waiters.size, mac)
                        waiters map { _ success mac }
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
     * @param actorSystem
     * @tparam T
     * @return
     */
    private def promiseOnExpire[T](promise: Promise[T], expiry: Long,
                        onExpire: (Promise[T]) => Unit)
                       (implicit actorSystem: ActorSystem): Promise[T] = {
        val now = Platform.currentTime
        if (now >= expiry) {
            if (promise tryComplete Left(new TimeoutException())) {
                actorSystem.dispatcher.execute(new Runnable {
                    def run = onExpire(promise)
                })
            }
            return promise
        }
        val when = Duration.create(expiry - now, TimeUnit.MILLISECONDS)
        val exp = actorSystem.scheduler.scheduleOnce(when) {
            if (promise tryComplete Left(new TimeoutException()))
                onExpire(promise)
        }
        promise onComplete {
            case _ =>
                if (!exp.isCancelled)
                    exp.cancel()
        }
        promise
    }

    private def fetchArpCacheEntry(ip: IPv4Addr, expiry: Long)
        (implicit ec: ExecutionContext) : Future[ArpCacheEntry] = {
        val promise = Promise[ArpCacheEntry]()(ec)
        arpCache.get(ip, new Callback1[ArpCacheEntry] {
            def call(value: ArpCacheEntry) { promise.success(value) }
        }, expiry)
        promise.future
    }

    private def removeArpWaiter(ip: IPv4Addr, promise: Promise[MAC]) {
        arpWaiters.removeBinding(ip, promise)
    }

    def waitForArpEntry(ip: IPv4Addr, expiry: Long)
                       (implicit ec: ExecutionContext,
                        actorSystem: ActorSystem) : Promise[MAC] = {
        val promise = Promise[MAC]()(ec)
        arpWaiters.addBinding(ip, promise)
        promiseOnExpire[MAC](promise, expiry, p => removeArpWaiter(ip, p))
    }

    def get(ip: IPv4Addr, port: RouterPort, expiry: Long)
           (implicit ec: ExecutionContext,
            actorSystem: ActorSystem,
            pktContext: PacketContext): Future[MAC] = {
        log.debug("Resolving MAC for {}", ip)
        /*
         * We must invoke waitForArpEntry() before requesting the ArpCacheEntry.
         * Otherwise there would be a race condition:
         *   - get() asks for the ArpCacheEntry
         *   - get() receives null for the entry, it does not exist.
         *   - at this point, the entry is populated because an ARP
         *     reply arrives to a node. This ArpTableImpl is notified but no one
         *     is waiting for this entry.
         *   - get() We calls waitForArpEntry too late, missing the notification.
         */
        val macPromise = waitForArpEntry(ip, expiry)
        val entryFuture = fetchArpCacheEntry(ip, expiry)
        entryFuture onSuccess {
            case entry =>
                val now = Platform.currentTime
                if (entry == null ||
                        (entry.stale < now &&
                         entry.lastArp+ARP_RETRY_MILLIS < now)  ||
                        entry.macAddr == null)
                    arpForAddress(ip, entry, port)
        }
        // macPromise may complete with a Left(TimeoutException). Use
        // fallbackTo so that exceptions don't escape the ArpTable class.
        entryFuture flatMap {
            case entry: ArpCacheEntry =>
                if (entry != null && entry.macAddr != null && entry.expiry >= Platform.currentTime) {
                    removeArpWaiter(ip, macPromise)
                    Promise.successful(entry.macAddr)
                } else
                    macPromise.future
            case _ =>
                macPromise.future
        } fallbackTo { Promise.successful(null) }
    }

    def set(ip: IPv4Addr, mac: MAC) (implicit actorSystem: ActorSystem) {
        arpWaiters.remove(ip) match {
                case Some(waiters) => waiters map { _ success mac}
                case None =>
        }
        val now = Platform.currentTime
        fetchArpCacheEntry(ip, now + 1000) onComplete {
            case Right(entry) if (entry != null &&
                                  entry.macAddr != null &&
                                  entry.stale > now &&
                                  entry.macAddr == mac) =>
                log.debug("Skipping write to ArpCache because a non-stale " +
                    "entry for {} with the same value ({}) exists.", ip, mac)
            case option =>
                log.debug("Got address for {}: {}", ip, mac)
                val entry = new ArpCacheEntry(mac, now + ARP_EXPIRATION_MILLIS,
                    now + ARP_STALE_MILLIS, 0)
                arpCache.add(ip, entry)
                val when = Duration.create(ARP_EXPIRATION_MILLIS,
                    TimeUnit.MILLISECONDS)
                actorSystem.scheduler.scheduleOnce(when){ expireCacheEntry(ip) }
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
        val now = Platform.currentTime
        val entryFuture = fetchArpCacheEntry(ip, now + ARP_RETRY_MILLIS)
        entryFuture onSuccess {
            case entry if entry != null =>
                if (entry.expiry <= Platform.currentTime)
                    arpCache.remove(ip)
            // TODO(pino): else retry the removal?
        }
    }

    private def arpForAddress(ip: IPv4Addr, entry: ArpCacheEntry,
                              port: RouterPort)
                             (implicit ec: ExecutionContext,
                              actorSystem: ActorSystem,
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
            DeduplicationActor.getRef(actorSystem) !
                EmitGeneratedPacket(port.id, arp,
                      if (pktContext != null) pktContext.flowCookie else None)
            // we don't retry for stale entries.
            if (cacheEntry.macAddr == null) {
                waitForArpEntry(ip, now + ARP_RETRY_MILLIS).future onComplete {
                    case Left(ex: TimeoutException) =>
                        retryLoopTopHalf(arp, now)
                    case Left(ex) =>
                        log.error("waitForArpEntry unexpected exception {}", ex)
                    case Right(mac) =>
                }
            }
        }

        def retryLoopTopHalf(arp: Ethernet, previous: Long) {
            val now = Platform.currentTime
            val entryFuture = fetchArpCacheEntry(ip, now + ARP_RETRY_MILLIS)
            entryFuture onSuccess {
                case null => retryLoopBottomHalf(null, arp, previous)
                case e => retryLoopBottomHalf(e.clone(), arp, previous)
            }
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
            actorSystem.scheduler.scheduleOnce(when){ expireCacheEntry(ip) }
        } else {
            // XXX race: when this key is re-added to the map someone else
            // may have written to it.
            newEntry = entry.clone()
        }

        val pkt = makeArpRequest(port.portMac, port.portAddr.getAddress, ip)
        retryLoopBottomHalf(newEntry, pkt, 0)
    }

}
