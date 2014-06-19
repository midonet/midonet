/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data.dhcp;

import org.midonet.cluster.data.Entity;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

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

    public IPv4Addr getIp() {
        return getData().ip;
    }

    public Host setIp(IPv4Addr ip) {
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
        public IPv4Addr ip;
        public String name;

    }
}
