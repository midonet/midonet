/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data.dhcp;

import org.midonet.cluster.data.Entity;
import org.midonet.packets.IntIPv4;

import java.util.List;

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

    public IntIPv4 getSubnetAddr() {
        return getData().subnetAddr;
    }

    public Subnet setSubnetAddr(IntIPv4 subnetAddr) {
        getData().subnetAddr = subnetAddr;
        return self();
    }

    public IntIPv4 getServerAddr() {
        return getData().serverAddr;
    }

    public Subnet setServerAddr(IntIPv4 serverAddr) {
        getData().serverAddr = serverAddr;
        return self();
    }

    public List<IntIPv4> getDnsServerAddrs() {
        return getData().dnsServerAddrs;
    }

    public Subnet setDnsServerAddrs(List<IntIPv4> dnsServerAddrs) {
        getData().dnsServerAddrs = dnsServerAddrs;
        return self();
    }

    public IntIPv4 getDefaultGateway() {
        return getData().defaultGateway;
    }

    public Subnet setDefaultGateway(IntIPv4 defaultGateway) {
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

    public static class Data {

        public IntIPv4 subnetAddr;
        public IntIPv4 serverAddr;
        public IntIPv4 defaultGateway;
        short interfaceMTU;
        public List<Opt121> opt121Routes;
        public List<IntIPv4> dnsServerAddrs;

        @Override
        public String toString() {
            return "Subnet{" +
                    "subnetAddr=" + subnetAddr +
                    ", serverAddr=" + serverAddr +
                    ", dnsServerAddrs=" + dnsServerAddrs +
                    ", interfaceMTU=" + interfaceMTU +
                    ", defaultGateway=" + defaultGateway +
                    ", opt121Routes=" + opt121Routes +
                    '}';
        }
    }
}
