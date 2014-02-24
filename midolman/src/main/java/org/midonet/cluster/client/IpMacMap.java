/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.cluster.client;

import org.midonet.packets.IPAddr;
import org.midonet.packets.MAC;
import org.midonet.util.functors.Callback3;

public interface IpMacMap<T extends IPAddr> {
    MAC get(T ip);
    void notify(Callback3<T, MAC, MAC> cb);
}
