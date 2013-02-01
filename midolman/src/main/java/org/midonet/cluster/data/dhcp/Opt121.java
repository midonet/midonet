/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data.dhcp;

import org.midonet.cluster.data.Entity;
import org.midonet.packets.IntIPv4;

import java.util.UUID;

/**
 * DHCP option 121
 */
public class Opt121 {

    private IntIPv4 rtDstSubnet;
    private IntIPv4 gateway;

    public IntIPv4 getGateway() {
        return gateway;
    }

    public Opt121 setGateway(IntIPv4 gateway) {
        this.gateway = gateway;
        return this;
    }

    public IntIPv4 getRtDstSubnet() {
        return rtDstSubnet;
    }

    public Opt121 setRtDstSubnet(IntIPv4 rtDstSubnet) {
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
