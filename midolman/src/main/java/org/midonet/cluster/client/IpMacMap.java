/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.cluster.client;

import org.midonet.packets.IPAddr;
import org.midonet.packets.MAC;

public interface IpMacMap<T extends IPAddr> {
    MAC get(T ip);
}
