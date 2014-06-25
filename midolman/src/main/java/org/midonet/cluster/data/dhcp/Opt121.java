/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data.dhcp;

import java.util.UUID;

import org.midonet.cluster.data.Entity;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;

/**
 * DHCP option 121
 */
public class Opt121 {

    private IPv4Subnet rtDstSubnet;
    private IPv4Addr gateway;

    public IPv4Addr getGateway() {
        return gateway;
    }

    public Opt121 setGateway(IPv4Addr gateway) {
        this.gateway = gateway;
        return this;
    }

    public IPv4Subnet getRtDstSubnet() {
        return rtDstSubnet;
    }

    public Opt121 setRtDstSubnet(IPv4Subnet rtDstSubnet) {
        this.rtDstSubnet = rtDstSubnet;
        return this;
    }

    @Override
    public String toString() {
        return "Opt121{" +
            "gateway=" + gateway +
            ", rtDstSubnet=" + rtDstSubnet +
            '}';
    }
}
