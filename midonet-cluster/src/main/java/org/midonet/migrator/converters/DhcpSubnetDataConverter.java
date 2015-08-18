/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.migrator.converters;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.midonet.cluster.data.dhcp.Opt121;
import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.cluster.data.dhcp.Subnet6;
import org.midonet.cluster.rest_api.models.DhcpOption121;
import org.midonet.cluster.rest_api.models.DhcpSubnet;
import org.midonet.cluster.rest_api.models.DhcpSubnet6;
import org.midonet.packets.IPv4Addr;

public class DhcpSubnetDataConverter {

    public static DhcpSubnet fromData(Subnet subnet, UUID bridgeId) {
        DhcpSubnet dto = new DhcpSubnet();
        dto.bridgeId = bridgeId;
        dto.subnetAddress = subnet.getSubnetAddr();
        dto.subnetLength = subnet.getSubnetAddr().getPrefixLen();
        dto.subnetPrefix = subnet.getSubnetAddr().getAddress().toString();

        IPv4Addr gway = subnet.getDefaultGateway();
        if (null != gway) {
            dto.defaultGateway = gway.toString();
        }

        IPv4Addr srvAddr = subnet.getServerAddr();
        if (null != srvAddr) {
            dto.serverAddr = srvAddr.toString();
        }

        if (null != subnet.getDnsServerAddrs()) {
            List<String> dnsSrvAddrs = new ArrayList<>();
            for (IPv4Addr ipAddr : subnet.getDnsServerAddrs()) {
                dnsSrvAddrs.add(ipAddr.toString());
            }
            dto.dnsServerAddrs = dnsSrvAddrs;
        }

        int intfMTU = subnet.getInterfaceMTU();
        if (intfMTU != 0) {
            dto.interfaceMTU = intfMTU;
        }

        List<DhcpOption121> routes = new ArrayList<>();
        if (null != subnet.getOpt121Routes()) {
            for (Opt121 opt : subnet.getOpt121Routes())
                routes.add(DhcpOption121DataConverter.fromData(opt));
        }
        dto.opt121Routes = routes;
        dto.enabled = subnet.isEnabled();

        return dto;
    }

    public static DhcpSubnet6 fromDataV6(Subnet6 subnet, UUID bridgeId) {
        DhcpSubnet6 d = new DhcpSubnet6();
        d.prefix = subnet.getPrefix().getAddress().toString();
        d.prefixLength = subnet.getPrefix().getPrefixLen();
        d.bridgeId = bridgeId;
        return d;
    }

}
