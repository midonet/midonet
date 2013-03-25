/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.cluster.client;

import org.midonet.packets.IntIPv4;
import org.midonet.packets.MAC;
import org.midonet.util.functors.Callback1;
import org.midonet.util.functors.Callback3;

public interface Ip4MacMap {
    void get(IntIPv4 ip, Callback1<MAC> cb, Long expirationTime);

    void notify(Callback3<IntIPv4, MAC, MAC> cb);
}
