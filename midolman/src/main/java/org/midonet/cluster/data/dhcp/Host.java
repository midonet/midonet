/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data.dhcp;

import org.midonet.cluster.data.Entity;
import org.midonet.packets.IntIPv4;
import org.midonet.packets.MAC;

import java.util.UUID;

/**
 * DHCP host
 */
public class Host extends Entity.Base<MAC, Host.Data, Host> {

    public Host() {
        this(null, new Data());
    }

    public Host(MAC mac, Data data) {
        super(mac, data);
    }

    @Override
    protected Host self() {
        return this;
    }

    public MAC getMAC() {
        return getData().mac;
    }

    public Host setMAC(MAC mac) {
        getData().mac = mac;
        return self();
    }

    public IntIPv4 getIp() {
        return getData().ip;
    }

    public Host setIp(IntIPv4 ip) {
        getData().ip = ip;
        return self();
    }

    public String getName() {
        return getData().name;
    }

    public Host setName(String name) {
        getData().name = name;
        return self();
    }

    public static class Data {

        public MAC mac;
        public IntIPv4 ip;
        public String name;

    }
}
