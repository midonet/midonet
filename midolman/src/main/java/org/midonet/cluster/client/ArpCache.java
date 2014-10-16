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
package org.midonet.cluster.client;

import org.midonet.midolman.state.ArpCacheEntry;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.util.functors.Callback3;

public interface ArpCache {
    /*
     * Allowing a blocking call, but implementations should ensure that they
     * serve the request off a local cache, not causing blocking IO.
     */
    ArpCacheEntry get(IPv4Addr ipAddr);
    void add(IPv4Addr ipAddr, ArpCacheEntry entry);
    void remove(IPv4Addr ipAddr);
    void notify(Callback3<IPv4Addr, MAC, MAC> cb);
    void unsubscribe(Callback3<IPv4Addr, MAC, MAC> cb);
    java.util.UUID getRouterId();
}
