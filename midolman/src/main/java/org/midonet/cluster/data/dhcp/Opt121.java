/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data.dhcp;

import java.util.UUID;

import org.midonet.cluster.data.Entity;
import org.midonet.packets.IntIPv4;
import org.midonet.packets.IPv4Subnet;

/**
 * DHCP option 121
 */
public class Opt121 {

    private IntIPv4 rtDstSubnet;
    private IntIPv4 gateway;

    public IntIPv4 getGateway() {
        return gateway;
    }

    public Opt121 setGateway(IPv4Subnet gateway) {
        this.gateway = IntIPv4.fromIPv4Subnet(gateway);
        return this;
    }

    public IntIPv4 getRtDstSubnet() {
        return rtDstSubnet;
    }

    public Opt121 setRtDstSubnet(IPv4Subnet rtDstSubnet) {
        this.rtDstSubnet = IntIPv4.fromIPv4Subnet(rtDstSubnet);
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
