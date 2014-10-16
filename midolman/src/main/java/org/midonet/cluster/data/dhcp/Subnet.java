/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
