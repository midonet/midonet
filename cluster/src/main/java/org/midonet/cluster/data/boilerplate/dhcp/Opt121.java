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
package org.midonet.cluster.data.boilerplate.dhcp;

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
}
