/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data.dhcp;

import java.util.List;

import org.midonet.cluster.data.Entity;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;

/**
 * DHCP subnet
 */
public class Subnet extends Entity.Base<String, Subnet.Data, Subnet> {

    public Subnet() {
        this(null, new Data());
    }

    public Subnet(String addr, Data data) {
        super(addr, data);
    }

    @Override
    protected Subnet self() {
        return this;
    }

    public IPv4Subnet getSubnetAddr() {
        return getData().subnetAddr;
    }

    public Subnet setSubnetAddr(IPv4Subnet subnetAddr) {
        getData().subnetAddr = subnetAddr;
        return self();
    }

    public IPv4Addr getServerAddr() {
        return getData().serverAddr;
    }

    public Subnet setServerAddr(IPv4Addr serverAddr) {
        getData().serverAddr = serverAddr;
        return self();
    }

    public List<IPv4Addr> getDnsServerAddrs() {
        return getData().dnsServerAddrs;
    }

    public Subnet setDnsServerAddrs(List<IPv4Addr> dnsServerAddrs) {
        getData().dnsServerAddrs = dnsServerAddrs;
        return self();
    }

    public IPv4Addr getDefaultGateway() {
        return getData().defaultGateway;
    }

    public Subnet setDefaultGateway(IPv4Addr defaultGateway) {
        getData().defaultGateway = defaultGateway;
        return self();
    }

    public short getInterfaceMTU() {
        return getData().interfaceMTU;
    }

    public Subnet setInterfaceMTU(short interfaceMTU) {
        getData().interfaceMTU = interfaceMTU;
        return self();
    }

    public List<Opt121> getOpt121Routes() {
        return getData().opt121Routes;
    }

    public Subnet setOpt121Routes(List<Opt121> opt121Routes) {
        getData().opt121Routes = opt121Routes;
        return self();
    }

    public Boolean isEnabled() {
        return getData().enabled;
    }

    public Subnet setEnabled(Boolean enabled) {
        getData().enabled = enabled;
        return self();
    }

    public Boolean isReplyReady() {
        return (getData().defaultGateway != null) &&
               (getData().serverAddr != null);
    }

    public static class Data {

        public IPv4Subnet subnetAddr;
        public IPv4Addr serverAddr;
        public IPv4Addr defaultGateway;
        short interfaceMTU;
        public List<Opt121> opt121Routes;
        public List<IPv4Addr> dnsServerAddrs;
        public Boolean enabled;

        @Override
        public String toString() {
            return "Subnet{" +
                    "subnetAddr=" + subnetAddr +
                    ", serverAddr=" + serverAddr +
                    ", dnsServerAddrs=" + dnsServerAddrs +
                    ", interfaceMTU=" + interfaceMTU +
                    ", defaultGateway=" + defaultGateway +
                    ", opt121Routes=" + opt121Routes +
                    ", enabled=" + enabled +
                    '}';
        }
    }
}
