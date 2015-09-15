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

package org.midonet.cluster;

import java.util.UUID;

import org.slf4j.Logger;

import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.midolman.state.ArpCache;
import org.midonet.midolman.state.ArpCacheEntry;
import org.midonet.midolman.state.ArpCacheUpdate;
import org.midonet.midolman.state.ArpTable;
import org.midonet.packets.IPv4Addr;
import org.midonet.util.eventloop.Reactor;

import static org.slf4j.LoggerFactory.getLogger;

public class ArpCacheImpl implements ArpCache,
                                     ArpTable.Watcher<IPv4Addr, ArpCacheEntry> {

    private final Logger log = getLogger("org.midonet.midolman.arp-cache");

    public final UUID routerId;
    ArpTable arpTable;
    Reactor reactor;
    private final Subject<ArpCacheUpdate, ArpCacheUpdate> updates =
        PublishSubject.create();

    public ArpCacheImpl(ArpTable arpTable, UUID routerId, Reactor reactor) {
        this.routerId = routerId;
        this.arpTable = arpTable;
        this.reactor = reactor;
        this.arpTable.addWatcher(this);
    }

    @Override
    public UUID routerId() {
        return routerId;
    }

    @Override
    public void processChange(IPv4Addr key, ArpCacheEntry oldV,
                              ArpCacheEntry newV) {
        if (oldV == null && newV == null)
            return;
        if (newV != null && oldV != null) {
            if (newV.macAddr == null && oldV.macAddr == null)
                return;
            if (newV.macAddr != null && oldV.macAddr != null &&
                newV.macAddr.equals(oldV.macAddr)) {
                return;
            }
        }

        updates.onNext(new ArpCacheUpdate(
            key,
            oldV != null ? oldV.macAddr : null,
            newV != null ? newV.macAddr : null));
    }

    @Override
    public ArpCacheEntry get(final IPv4Addr ipAddr) {
        // It's ok to do a synchronous get on the map because it only
        // queries local state (doesn't go remote like the other calls.
        return arpTable.get(ipAddr);
    }

    @Override
    public void add(final IPv4Addr ipAddr, final ArpCacheEntry entry) {
        reactor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    arpTable.put(ipAddr, entry);
                } catch (Exception e) {
                    log.error("Failed adding ARP entry. IP: {} MAC: {}",
                              ipAddr, entry, e);
                }
            }});
    }

    @Override
    public void remove(final IPv4Addr ipAddr) {
        reactor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    arpTable.removeIfOwner(ipAddr);
                } catch (Exception e) {
                    log.error("Could not remove Arp entry for IP: {}",
                              ipAddr, e);
                }
            }});
    }

    public Observable<ArpCacheUpdate> observable() {
        return updates.asObservable();
    }
}
