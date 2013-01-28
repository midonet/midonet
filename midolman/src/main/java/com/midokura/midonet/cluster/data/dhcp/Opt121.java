/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.cluster.data.dhcp;

import com.midokura.midonet.cluster.data.Entity;
import com.midokura.packets.IntIPv4;

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
