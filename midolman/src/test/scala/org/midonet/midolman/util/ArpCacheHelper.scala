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
package org.midonet.midolman.util

import org.midonet.cluster.data.storage.ArpCacheEntry
import org.midonet.midolman.simulation.Router
import org.midonet.midolman.state.ArpCache
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.UnixClock

object ArpCacheHelper {
    var ARP_EXPIRATION_MILLIS = 10000L
    var ARP_STALE_MILLIS = 1800000L
    def feedArpCache(cache: ArpCache, ip: IPv4Addr, mac: MAC) {
        val entry = new ArpCacheEntry(mac, UnixClock().time + ARP_EXPIRATION_MILLIS,
                                      UnixClock().time + ARP_STALE_MILLIS, 0)
        cache.add(ip, entry)
    }

    def feedArpCache(router: Router, ip: IPv4Addr, mac: MAC) {
        feedArpCache(router.arpCache, ip, mac)
    }
}
