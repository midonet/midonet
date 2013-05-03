/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.cluster.client;

import org.midonet.midolman.state.ArpCacheEntry;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.util.functors.Callback1;
import org.midonet.util.functors.Callback2;


/*
 * Non-blocking.
 */
public interface ArpCache {
    void get(IPv4Addr ipAddr, Callback1<ArpCacheEntry> cb, Long expirationTime);
    void add(IPv4Addr ipAddr, ArpCacheEntry entry);
    void remove(IPv4Addr ipAddr);
    void notify(Callback2<IPv4Addr, MAC> cb);
    void unsubscribe(Callback2<IPv4Addr, MAC> cb);
}
