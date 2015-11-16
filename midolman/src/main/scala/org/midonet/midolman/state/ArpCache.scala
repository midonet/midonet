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

package org.midonet.midolman.state

import java.util.UUID

import javax.annotation.concurrent.ThreadSafe

import com.typesafe.scalalogging.Logger

import rx.subjects.PublishSubject
import rx.{Subscriber, Observable}
import rx.Observable.OnSubscribe

import org.midonet.cluster.backend.zookeeper.StateAccessException
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.packets.{MAC, IPv4Addr}
import org.midonet.util.functors.makeRunnable

/** An ARP cache update. */
case class ArpCacheUpdate(ipAddr: IPv4Addr, oldMac: MAC, newMac: MAC)

/**
 * A trait for a router's ARP cache.
 */
trait ArpCache {
    def get(ipAddr: IPv4Addr): ArpCacheEntry
    def add(ipAddr: IPv4Addr, entry: ArpCacheEntry): Unit
    def remove(ipAddr: IPv4Addr): Unit
    def routerId: UUID
    def observable: Observable[ArpCacheUpdate]
}

object ArpCache {

    /**
     * Creates an [[Observable]] that emits a single notification with an ARP
     * cache for the specified router.
     */
    def createAsObservable(vt: VirtualTopology, routerId: UUID) =
        Observable.create(new OnSubscribeArpCache(vt, routerId))

    /**
     * Implements the [[OnSubscribe]] interface for an observable that emits
     * only one notification with the current ARP cache for the given router
     * and then completes. The observable may emit the notification either at
     * the moment of subscription, or later if the connection to storage fails.
     */
    private class OnSubscribeArpCache(vt: VirtualTopology, routerId: UUID)
        extends OnSubscribe[ArpCache] with MidolmanLogging {

        override def logSource =
            s"org.midonet.devices.router.router-$routerId.arp-cache"

        @ThreadSafe
        protected override def call(child: Subscriber[_ >: ArpCache]): Unit = {
            log.debug("Subscribing to the ARP cache")
            try {
                val arpCache = new RouterArpCache(vt, routerId, log)
                child.onNext(arpCache)
                child.onCompleted()
            } catch {
                case e: StateAccessException =>
                    // If initializing the ARP cache fails, use the connection
                    // watcher to retry emitting the ARP cache notification for
                    // the subscriber.
                    vt.connectionWatcher.handleError(
                        routerId.toString, makeRunnable { call(child) }, e)
            }
        }
    }

    /**
     * Implements the [[ArpCache]] for a router.
     */
    @throws[StateAccessException]
    private class RouterArpCache(vt: VirtualTopology,
                                 override val routerId: UUID, log: Logger)
        extends ArpCache with MidolmanLogging {

        private val subject = PublishSubject.create[ArpCacheUpdate]()
        private val watcher =
            new ReplicatedMap.Watcher[IPv4Addr, ArpCacheEntry] {
                override def processChange(ipAddr: IPv4Addr,
                                           oldEntry: ArpCacheEntry,
                                           newEntry: ArpCacheEntry): Unit = {

                    if ((oldEntry eq null) && (newEntry eq null)) return
                    if ((oldEntry ne null) && (newEntry ne null) &&
                        (oldEntry.macAddr == newEntry.macAddr)) return

                    subject.onNext(ArpCacheUpdate(
                        ipAddr,
                        if (oldEntry ne null) oldEntry.macAddr else null,
                        if (newEntry ne null) newEntry.macAddr else null
                        ))
                }
            }
        private val arpTable = vt.state.routerArpTable(routerId)

        arpTable.addWatcher(watcher)
        arpTable.start()

        /** Gets an entry from the underlying ARP table. The request only
          * queries local state. */
        override def get(ipAddr: IPv4Addr): ArpCacheEntry = arpTable.get(ipAddr)
        /** Adds an ARP entry to the underlying ARP table. The operation is
          * scheduled on the topology IO executor. */
        override def add(ipAddr: IPv4Addr, entry: ArpCacheEntry): Unit = {
            vt.executeIo {
                try {
                    arpTable.put(ipAddr, entry)
                } catch {
                    case e: Exception =>
                        log.error("Failed to add ARP entry IP: {} Entry: {}",
                                  ipAddr, entry, e)
                }
            }
        }
        /** Removes an ARP entry from the underlying ARP table. The operation is
          * scheduled on the topology IO executor. */
        override def remove(ipAddr: IPv4Addr): Unit = {
            vt.executeIo {
                try {
                    arpTable.removeIfOwner(ipAddr)
                } catch {
                    case e: Exception =>
                        log.error("Failed to remove ARP entry for IP: {}",
                                  ipAddr, e)
                }
            }
        }
        /** Observable that emits ARP cache updates. */
        override def observable: Observable[ArpCacheUpdate] =
            subject.asObservable()
    }
}