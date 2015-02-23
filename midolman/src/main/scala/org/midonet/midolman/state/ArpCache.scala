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

import rx.Observable

import org.midonet.packets.{MAC, IPv4Addr}

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
