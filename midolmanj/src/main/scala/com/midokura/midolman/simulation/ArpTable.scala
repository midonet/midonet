/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.simulation

import collection.mutable
import compat.Platform
import akka.dispatch.{ExecutionContext, Future, Promise}
import akka.dispatch.Future.flow
import akka.util.Duration
import java.util.concurrent.{TimeUnit, TimeoutException}
import org.slf4j.LoggerFactory

import com.midokura.midolman.SimulationController
import com.midokura.packets.{ARP, Ethernet, IntIPv4, IPv4, MAC}
import akka.actor.ActorSystem
import com.midokura.midonet.cluster.client.{ArpCache, RouterPort}
import com.midokura.midolman.SimulationController.EmitGeneratedPacket
import com.midokura.midolman.state.ArpCacheEntry
import com.midokura.util.functors.{Callback2, Callback1}

/* The ArpTable is called from the Coordinators' actors and
 * processes and schedules ARPs. */
trait ArpTable {
    def get(ip: IntIPv4, port: RouterPort[_], expiry: Long)
          (implicit ec: ExecutionContext, actorSystem: ActorSystem): Future[MAC]
    def set(ip: IntIPv4, mac: MAC) (implicit actorSystem: ActorSystem)
    def start()
    def stop()
}

class ArpTableImpl(arpCache: ArpCache) extends ArpTable {
    private val log = LoggerFactory.getLogger(classOf[ArpTableImpl])
    private val ARP_RETRY_MILLIS = 10 * 1000
    private val ARP_TIMEOUT_MILLIS = 60 * 1000
    private val ARP_STALE_MILLIS = 1800 * 1000
    private val ARP_EXPIRATION_MILLIS = 3600 * 1000
    private val arpWaiters = new mutable.HashMap[IntIPv4,
                                             mutable.Set[Promise[MAC]]] with
            mutable.MultiMap[IntIPv4, Promise[MAC]] with
            mutable.SynchronizedMap[IntIPv4, mutable.Set[Promise[MAC]]]
    private var arpCacheCallback: Callback2[IntIPv4, MAC] = null


    override def start() {
        arpCacheCallback = new Callback2[IntIPv4, MAC] {
            def call(ip: IntIPv4, mac: MAC) {
                arpWaiters.remove(ip) match {
                    case Some(waiters) => waiters map { _ success mac }
                    case None =>
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
                       (implicit actorSystem: ActorSystem): Future[T] = {
        val now = Platform.currentTime
        if (now >= expiry) {
            if (promise tryComplete Left(new TimeoutException()))
                onExpire(promise)
            return promise
        }
        val when = Duration.create(expiry - now, TimeUnit.MILLISECONDS)
        val exp = actorSystem.scheduler.scheduleOnce(when) {
            if (promise tryComplete Left(new TimeoutException()))
                onExpire(promise)
        }
        promise onSuccess { case _ => exp.cancel() }
        promise
    }

    private def fetchArpCacheEntry(ip: IntIPv4, expiry: Long)
        (implicit ec: ExecutionContext) : Future[ArpCacheEntry] = {
        val promise = Promise[ArpCacheEntry]()(ec)
        arpCache.get(ip, new Callback1[ArpCacheEntry] {
            def call(value: ArpCacheEntry) {
                promise.success(value)
            }
        }, expiry)
        promise
    }

    private def removeWatcher(ip: IntIPv4, future: Future[MAC]) {
        arpWaiters.get(ip) match {
            case Some(waiters) => future match {
                case promise: Promise[MAC] => waiters remove promise
                case _ =>
            }
            case None =>
        }
    }

    def waitForArpEntry(ip: IntIPv4, expiry: Long)
                       (implicit ec: ExecutionContext,
                        actorSystem: ActorSystem) : Future[MAC] = {
        val promise = Promise[MAC]()(ec)
        /* MultiMap isn't thread-safe and the SynchronizedMap trait does not
         * help either. See: https://issues.scala-lang.org/browse/SI-6087
         * and/or the MultiMap and SynchronizedMap source files.
         */
        arpWaiters.synchronized { arpWaiters.addBinding(ip, promise) }
        promiseOnExpire[MAC](promise, expiry, p => removeWatcher(ip, p))
    }

    def get(ip: IntIPv4, port: RouterPort[_], expiry: Long)
           (implicit ec: ExecutionContext,
            actorSystem: ActorSystem): Future[MAC] = {
        val entryFuture = fetchArpCacheEntry(ip, expiry)
        entryFuture onSuccess {
            case entry =>
                if (entry == null ||
                        entry.stale < Platform.currentTime ||
                        entry.macAddr == null)
                    arpForAddress(ip, entry, port)
        }
        flow {
            val entry = entryFuture()
            if (entry != null && entry.expiry >= Platform.currentTime)
                entry.macAddr
            else {
                // There's no arpCache entry, or it's expired.
                // Wait for the arpCache to become populated by an ARP reply
                waitForArpEntry(ip, expiry).apply()
            }
        }(ec)
    }

    def set(ip: IntIPv4, mac: MAC) (implicit actorSystem: ActorSystem) {
        arpWaiters.remove(ip) match {
                case Some(waiters) => waiters map { _ success mac}
                case None =>
        }
        val now = Platform.currentTime
        val entry = new ArpCacheEntry(mac, now + ARP_EXPIRATION_MILLIS,
                                      now + ARP_STALE_MILLIS, 0)
        arpCache.add(ip, entry)
        val when = Duration.create(ARP_EXPIRATION_MILLIS,
            TimeUnit.MILLISECONDS)
        actorSystem.scheduler.scheduleOnce(when){ expireCacheEntry(ip) }
    }

    private def makeArpRequest(srcMac: MAC, srcIP: IntIPv4, dstIP: IntIPv4):
                    Ethernet = {
        val arp = new ARP()
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET)
        arp.setProtocolType(ARP.PROTO_TYPE_IP)
        arp.setHardwareAddressLength(6.asInstanceOf[Byte])
        arp.setProtocolAddressLength(4.asInstanceOf[Byte])
        arp.setOpCode(ARP.OP_REQUEST)
        arp.setSenderHardwareAddress(srcMac)
        arp.setTargetHardwareAddress(MAC.fromString("00:00:00:00:00:00"))
        arp.setSenderProtocolAddress(
            IPv4.toIPv4AddressBytes(srcIP.addressAsInt))
        arp.setTargetProtocolAddress(
            IPv4.toIPv4AddressBytes(dstIP.addressAsInt))
        val pkt: Ethernet = new Ethernet
        pkt.setPayload(arp)
        pkt.setSourceMACAddress(srcMac)
        pkt.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"))
        pkt.setEtherType(ARP.ETHERTYPE)
    }

    // XXX
    // cancel scheduled expires when an entry is refreshed?
    // what happens if this node crashes before
    // expiration, is there a global clean-up task?
    private def expireCacheEntry(ip: IntIPv4)
                                (implicit ec: ExecutionContext) {
        val now = Platform.currentTime
        val entryFuture = fetchArpCacheEntry(ip, now + ARP_RETRY_MILLIS)
        entryFuture onSuccess {
            case entry if entry != null =>
                val now = Platform.currentTime
                if (entry.expiry <= now) {
                    arpWaiters.remove(ip) match {
                        case Some(waiters) => waiters map { _ success null }
                        case None =>
                    }
                    arpCache.remove(ip)
                }
        }
    }

    private def arpForAddress(ip: IntIPv4, entry: ArpCacheEntry,
                              port: RouterPort[_])
                             (implicit ec: ExecutionContext,
                              actorSystem: ActorSystem) {
        def retryLoopBottomHalf(cacheEntry: ArpCacheEntry, arp: Ethernet,
                                previous: Long) {
            val now = Platform.currentTime
            // expired, no retries left.
            if (cacheEntry == null || cacheEntry.expiry <= now) {
                arpWaiters.remove(ip) match {
                    case Some(waiters) => waiters map { _ success null}
                    case None =>
                }
                return
            }
            // another node took over, give up. Waiters will be notified.
            // XXX - what will happen to our waiters if another node takes
            // over and we time out??
            // We should do arpCache.set() with mac = null
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
            SimulationController.getRef(actorSystem) !
                EmitGeneratedPacket(port.id, arp)
            waitForArpEntry(ip, now + ARP_RETRY_MILLIS) onComplete {
                case Left(ex: TimeoutException) =>
                    retryLoopTopHalf(arp, now)
                case Left(ex) =>
                    log.error("waitForArpEntry unexpected exception {}", ex)
                case Right(mac) =>
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
        // XXX this is open to races with other nodes
        // for now we take over sending ARPs if no ARP has been sent
        // in RETRY_INTERVAL * 2. And, obviously, the MAC is null or stale.
        //
        // But when that happens (for some reason a node stopped following
        // through with the retries) other nodes will race to take over.
        if (entry != null && entry.lastArp + ARP_RETRY_MILLIS*2 > now)
            return

        var newEntry: ArpCacheEntry = null
        if (entry == null) {
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

        val pkt = makeArpRequest(port.portMac, port.portAddr, ip)
        retryLoopBottomHalf(newEntry, pkt, 0)
    }

}
