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

import java.util.Objects;
import java.util.UUID;

import org.midonet.cluster.data.Entity;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;

/**
 * DHCP option 121
 */
public class Opt121 {

    private IPv4Subnet rtDstSubnet;
    private IPv4Addr gateway;

    public IPv4Addr getGateway() {
        return gateway;
    }

    public Opt121() {}

    public Opt121(IPv4Subnet rtDstSubnet, IPv4Addr gateway) {
        this.rtDstSubnet = rtDstSubnet;
        this.gateway = gateway;
    }

    public Opt121 setGateway(IPv4Addr gateway) {
        this.gateway = gateway;
        return this;
    }

    public IPv4Subnet getRtDstSubnet() {
        return rtDstSubnet;
    }

    public Opt121 setRtDstSubnet(IPv4Subnet rtDstSubnet) {
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Opt121 other = (Opt121)o;
        return Objects.equals(rtDstSubnet, other.rtDstSubnet) &&
               Objects.equals(gateway, other.gateway);

    }
}
