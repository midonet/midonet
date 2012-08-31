/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */
package com.midokura.midonet.client.resource;

import com.midokura.midonet.client.VendorMediaType;
import com.midokura.midonet.client.WebResource;
import com.midokura.midonet.client.dto.DtoDhcpHost;
import com.midokura.midonet.client.dto.DtoDhcpOption121;
import com.midokura.midonet.client.dto.DtoDhcpSubnet;

import java.net.URI;
import java.util.List;

public class Subnet extends ResourceBase<Subnet, DtoDhcpSubnet> {

    public Subnet(WebResource resource, URI uriForCreation, DtoDhcpSubnet
            principalDto) {
        super(resource, uriForCreation, principalDto, VendorMediaType
                .APPLICATION_DHCP_SUBNET_JSON);
    }

    /**
     * Gets URI for this Subnet
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
    public Subnet defaultGateway(String defaultGateway) {
        principalDto.setDefaultGateway(defaultGateway);
        return this;
    }

    /**
     * Sets host.
     *
     * @param hosts
     * @return this
     */
    public Subnet hosts(URI hosts) {
        principalDto.setHosts(hosts);
        return this;
    }

    /**
     * Sets opt121Routes.
     *
     * @param opt121Routes
     * @return this
     */
    public Subnet opt121Routes(List<DtoDhcpOption121> opt121Routes) {
        principalDto.setOpt121Routes(opt121Routes);
        return this;
    }

    /**
     * Sets SubnetLength.
     *
     * @param subnetLength
     * @return this
     */
    public Subnet subnetLength(int subnetLength) {
        principalDto.setSubnetLength(subnetLength);
        return this;
    }

    /**
     * Sets prefix of the subnet.
     *
     * @param subnetPrefix
     * @return this.
     */
    public Subnet subnetPrefix(String subnetPrefix) {
        principalDto.setSubnetPrefix(subnetPrefix);
        return this;
    }

    /**
     * Gets host resources under this subnet.
     *
     * @return
     */
    public ResourceCollection<SubnetHost> getHosts() {
        return getChildResources(principalDto.getHosts(), VendorMediaType
                .APPLICATION_DHCP_HOST_COLLECTION_JSON, SubnetHost.class,
                DtoDhcpHost.class);
    }

    /**
     * Adds subnet host resource under this subnet.
     *
     * @return new SubnetHost()
     */
    public SubnetHost addSubnetHost() {
        return new SubnetHost(resource, principalDto.getHosts(),
                new DtoDhcpHost());
    }

    @Override
    public String toString() {
        return String.format("{Subnet, networkp=%s, length=%s}",
                principalDto.getSubnetPrefix(), principalDto.getSubnetLength());
    }
}
