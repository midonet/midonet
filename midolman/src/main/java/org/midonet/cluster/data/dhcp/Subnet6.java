/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.cluster.data.dhcp;

import org.midonet.cluster.data.Entity;
import org.midonet.packets.IPv6Subnet;

import java.util.List;

/**
 * DHCPv6 subnet6
 */
public class Subnet6 extends Entity.Base<String, Subnet6.Data, Subnet6> {

    public Subnet6() {
        this(null, new Data());
    }

    public Subnet6(String addr, Data data) {
        super(addr, data);
    }

    @Override
    protected Subnet6 self() {
        return this;
    }

    public IPv6Subnet getPrefix() {
        return getData().prefix;
    }

    public Subnet6 setPrefix(IPv6Subnet prefix) {
        getData().prefix = prefix;
        return self();
    }

    public static class Data {

        public IPv6Subnet prefix;

        @Override
        public String toString() {
            return "Subnet6{" +
                    "prefix=" + prefix.toString() +
                    '}';
        }
    }
}
