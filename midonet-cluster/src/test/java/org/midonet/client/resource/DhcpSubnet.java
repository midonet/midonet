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
package org.midonet.client.resource;

import java.net.URI;
import java.util.List;

import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoDhcpHost;
import org.midonet.client.dto.DtoDhcpOption121;
import org.midonet.client.dto.DtoDhcpSubnet;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;

import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_DHCP_SUBNET_JSON_V2;

public class DhcpSubnet extends ResourceBase<DhcpSubnet, DtoDhcpSubnet> {

    public DhcpSubnet(WebResource resource, URI uriForCreation, DtoDhcpSubnet
        principalDto) {
        super(resource, uriForCreation, principalDto,
              APPLICATION_DHCP_SUBNET_JSON_V2());
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public String getSubnetPrefix() {
        return principalDto.getSubnetPrefix();
    }

    public String getDefaultGateway() {
        return principalDto.getDefaultGateway();
    }

    public List<DtoDhcpOption121> getOpt121Routes() {
        return principalDto.getOpt121Routes();
    }

    public String getServerAddr() {
        return principalDto.getServerAddr();
    }

    public List<String> getDnsServerAddrs() {
        return principalDto.getDnsServerAddrs();
    }

    public int getInterfaceMTU() {
        return principalDto.getInterfaceMTU();
    }

    public int getSubnetLength() {
        return principalDto.getSubnetLength();
    }

    public DhcpSubnet defaultGateway(String defaultGateway) {
        principalDto.setDefaultGateway(defaultGateway);
        return this;
    }

    public DhcpSubnet hosts(URI hosts) {
        principalDto.setHosts(hosts);
        return this;
    }

    public DhcpSubnet opt121Routes(List<DtoDhcpOption121> opt121Routes) {
        principalDto.setOpt121Routes(opt121Routes);
        return this;
    }

    public DhcpSubnet subnetLength(int subnetLength) {
        principalDto.setSubnetLength(subnetLength);
        return this;
    }

    public DhcpSubnet subnetPrefix(String subnetPrefix) {
        principalDto.setSubnetPrefix(subnetPrefix);
        return this;
    }

    public DhcpSubnet serverAddr(String serverAddr) {
        principalDto.setServerAddr(serverAddr);
        return this;
    }

    public DhcpSubnet dnsServerAddrs(List<String> dnsServerAddr) {
        principalDto.setDnsServerAddrs(dnsServerAddr);
        return this;
    }

    public DhcpSubnet interfaceMTU(int interfaceMTU) {
        principalDto.setInterfaceMTU(interfaceMTU);
        return this;
    }

    public ResourceCollection<DhcpHost> getDhcpHosts() {
        return getChildResources(
            principalDto.getHosts(),
            null,
            MidonetMediaTypes.APPLICATION_DHCP_HOST_COLLECTION_JSON_V2(),
            DhcpHost.class,
            DtoDhcpHost.class);
    }

    public DhcpHost addDhcpHost() {
        return new DhcpHost(resource, principalDto.getHosts(),
                              new DtoDhcpHost());
    }

    @Override
    public String toString() {
        return String.format("{DhcpSubnet, networkp=%s, length=%s}",
                             principalDto.getSubnetPrefix(),
                             principalDto.getSubnetLength());
    }
}
