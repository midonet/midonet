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

import rx.{Observable, Subscriber}
import rx.Observable.OnSubscribe

import org.midonet.cluster.data.storage.model.ArpEntry
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.functors.makeFunc1
import org.midonet.util.logging.Logger

/** An ARP cache update. */
case class ArpCacheUpdate(ipAddr: IPv4Addr, oldMac: MAC, newMac: MAC)

/**
 * A trait for a router's ARP cache.
 */
trait ArpCache {
    def get(ipAddr: IPv4Addr): ArpEntry
    def add(ipAddr: IPv4Addr, entry: ArpEntry): Unit
    def remove(ipAddr: IPv4Addr): Unit
    def routerId: UUID
    def observable: Observable[ArpCacheUpdate]
    def close(): Unit
}

object ArpCache {

    /**
     * Creates an [[Observable]] that emits a single notification with an ARP
     * cache for the specified router.
     */
    def createAsObservable(vt: VirtualTopology, routerId: UUID, log: Logger) =
        Observable.create(new OnSubscribeArpCache(vt, routerId, log))

    /**
     * Implements the [[OnSubscribe]] interface for an observable that emits
     * only one notification with the current ARP cache for the given router
     * and then completes. The observable may emit the notification either at
     * the moment of subscription, or later if the connection to storage fails.
     */
    private class OnSubscribeArpCache(vt: VirtualTopology, routerId: UUID,
                                      log: Logger)
        extends OnSubscribe[ArpCache] {

        @ThreadSafe
        protected override def call(child: Subscriber[_ >: ArpCache]): Unit = {
            log.debug("Subscribing to the ARP cache")
            val arpCache = new RouterArpCache(vt, routerId, log)
            child.onNext(arpCache)
            child.onCompleted()
        }
    }

    /**
     * Implements the [[ArpCache]] for a router.
     */
    private class RouterArpCache(vt: VirtualTopology,
                                 override val routerId: UUID, log: Logger)
        extends ArpCache with MidolmanLogging {

        private val arpTable = vt.stateTables.routerArpTable(routerId)
        arpTable.start()

        /** Gets an entry from the underlying ARP table. The request only
          * queries local state. */
        override def get(ipAddr: IPv4Addr): ArpEntry = arpTable.getLocal(ipAddr)
        /** Adds an ARP entry to the underlying ARP table. The operation is
          * scheduled on the topology IO executor. */
        override def add(ipAddr: IPv4Addr, entry: ArpEntry): Unit = {
            vt.executeIo {
                try {
                    arpTable.add(ipAddr, entry)
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
                    arpTable.remove(ipAddr)
                } catch {
                    case e: Exception =>
                        log.error("Failed to remove ARP entry for IP: {}",
                                  ipAddr, e)
                }
            }
        }
        /** Observable that emits ARP cache updates. */
        override val observable: Observable[ArpCacheUpdate] = arpTable
            .observable
            .filter(makeFunc1 { update =>
                if ((update.oldValue eq null) && (update.newValue eq null)) false
                else if ((update.oldValue ne null) && (update.newValue ne null) &&
                         (update.oldValue.mac == update.newValue.mac)) false
                else true
            })
            .map[ArpCacheUpdate](makeFunc1 { update =>
                ArpCacheUpdate(
                    update.key,
                    if (update.oldValue ne null) update.oldValue.mac else null,
                    if (update.newValue ne null) update.newValue.mac else null)
            })
        /** Closes the ARP cache. */
        override def close(): Unit = {
            arpTable.stop()
        }
    }
}