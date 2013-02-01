/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.cluster.client;

import org.midonet.midolman.state.ArpCacheEntry;
import org.midonet.packets.IntIPv4;
import org.midonet.packets.MAC;
import org.midonet.util.functors.Callback1;
import org.midonet.util.functors.Callback2;


/*
 * Non-blocking.
 */
public interface ArpCache {
    void get(IntIPv4 ipAddr, Callback1<ArpCacheEntry> cb, Long expirationTime);
    void add(IntIPv4 ipAddr, ArpCacheEntry entry);
    void remove(IntIPv4 ipAddr);
    void notify(Callback2<IntIPv4, MAC> cb);
    void unsubscribe(Callback2<IntIPv4, MAC> cb);
}
