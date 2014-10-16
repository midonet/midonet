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
package org.midonet.client.resource;

import java.net.URI;
import java.util.List;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoDhcpHost;
import org.midonet.client.dto.DtoDhcpOption121;
import org.midonet.client.dto.DtoDhcpSubnet;

public class DhcpSubnet extends ResourceBase<DhcpSubnet, DtoDhcpSubnet> {

    public DhcpSubnet(WebResource resource, URI uriForCreation, DtoDhcpSubnet
        principalDto) {
        super(resource, uriForCreation, principalDto, VendorMediaType
            .APPLICATION_DHCP_SUBNET_JSON);
    }

    /**
     * Gets URI for this DhcpSubnet
     *
     * @return
     */
    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    /**
     * Gets prefix for this subnet.
     *
     * @return
     */
    public String getSubnetPrefix() {
        return principalDto.getSubnetPrefix();
    }

    /**
     * Gets default gateway for this subnet.
     *
     * @return
     */
    public String getDefaultGateway() {
        return principalDto.getDefaultGateway();
    }

    /**
     * Gets opt121Routes.
     *
     * @return
     */
    public List<DtoDhcpOption121> getOpt121Routes() {
        return principalDto.getOpt121Routes();
    }

    /**
     * Gets DHCP server IP.
     *
     * @return
     */
    public String getServerAddr() {
        return principalDto.getServerAddr();
    }

    /**
     * Gets DNS server IP.
     *
     * @return
     */
    public List<String> getDnsServerAddrs() {
        return principalDto.getDnsServerAddrs();
    }

    /**
     * Gets interface MTU.
     *
     * @return
     */
    public int getInterfaceMTU() {
        return principalDto.getInterfaceMTU();
    }

    /**
     * Gets length of the subnet address.
     *
     * @return
     */
    public int getSubnetLength() {
        return principalDto.getSubnetLength();
    }

    /**
     * Sets default gateway.
     *
     * @param defaultGateway
     * @return this
     */
    public DhcpSubnet defaultGateway(String defaultGateway) {
        principalDto.setDefaultGateway(defaultGateway);
        return this;
    }

    /**
     * Sets host.
     *
     * @param hosts
     * @return this
     */
    public DhcpSubnet hosts(URI hosts) {
        principalDto.setHosts(hosts);
        return this;
    }

    /**
     * Sets opt121Routes.
     *
     * @param opt121Routes
     * @return this
     */
    public DhcpSubnet opt121Routes(List<DtoDhcpOption121> opt121Routes) {
        principalDto.setOpt121Routes(opt121Routes);
        return this;
    }

    /**
     * Sets SubnetLength.
     *
     * @param subnetLength
     * @return this
     */
    public DhcpSubnet subnetLength(int subnetLength) {
        principalDto.setSubnetLength(subnetLength);
        return this;
    }

    /**
     * Sets prefix of the subnet.
     *
     * @param subnetPrefix
     * @return this.
     */
    public DhcpSubnet subnetPrefix(String subnetPrefix) {
        principalDto.setSubnetPrefix(subnetPrefix);
        return this;
    }

    /**
     * Sets DHCP Server IP.
     *
     * @param serverAddr
     * @return this
     */
    public DhcpSubnet serverAddr(String serverAddr) {
        principalDto.setServerAddr(serverAddr);
        return this;
    }

    /**
     * Sets DNS Server IP.
     *
     * @param dnsServerAddr
     * @return this
     */
    public DhcpSubnet dnsServerAddrs(List<String> dnsServerAddr) {
        principalDto.setDnsServerAddrs(dnsServerAddr);
        return this;
    }

    /**
     * Sets Interface MTU.
     *
     * @param interfaceMTU
     * @return this
     */
    public DhcpSubnet interfaceMTU(int interfaceMTU) {
        principalDto.setInterfaceMTU(interfaceMTU);
        return this;
    }

    /**
     * Gets host resources under this subnet.
     *
     * @return
     */
    public ResourceCollection<DhcpHost> getDhcpHosts() {
        return getChildResources(
            principalDto.getHosts(),
            null,
            VendorMediaType.APPLICATION_DHCP_HOST_COLLECTION_JSON,
            DhcpHost.class,
            DtoDhcpHost.class);
    }

    /**
     * Adds subnet host resource under this subnet.
     *
     * @return new DhcpHost()
     */
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
