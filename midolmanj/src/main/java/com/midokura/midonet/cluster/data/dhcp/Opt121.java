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
public class Opt121 extends Entity.Base<UUID, Opt121.Data, Opt121> {

    public Opt121() {
        this(null, new Data());
    }

    public Opt121(UUID uuid, Data data) {
        super(uuid, data);
    }

    @Override
    protected Opt121 self() {
        return this;
    }

    public IntIPv4 getGateway() {
        return getData().gateway;
    }

    public Opt121 setGateway(IntIPv4 gateway) {
        getData().gateway = gateway;
        return self();
    }

    public IntIPv4 getRtDstSubnet() {
        return getData().rtDstSubnet;
    }

    public Opt121 setRtDstSubnet(IntIPv4 rtDstSubnet) {
        getData().rtDstSubnet = rtDstSubnet;
        return self();
    }

    public static class Data {

        public IntIPv4 rtDstSubnet;
        public IntIPv4 gateway;

    }
}
