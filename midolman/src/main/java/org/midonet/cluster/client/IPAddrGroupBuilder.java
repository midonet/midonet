/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.client;

import org.midonet.packets.IPAddr;

import java.util.Set;

public interface IPAddrGroupBuilder {
    public void setAddrs(Set<IPAddr> addrs);
}
