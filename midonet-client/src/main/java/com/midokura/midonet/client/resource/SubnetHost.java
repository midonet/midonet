/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */
package com.midokura.midonet.client.resource;

import java.net.URI;

import com.midokura.midonet.client.VendorMediaType;
import com.midokura.midonet.client.WebResource;
import com.midokura.midonet.client.dto.DtoDhcpHost;

public class SubnetHost extends ResourceBase<SubnetHost, DtoDhcpHost> {

    public SubnetHost(WebResource resource, URI uriForCreation, DtoDhcpHost
            principalDto) {
        super(resource, uriForCreation, principalDto,
                VendorMediaType.APPLICATION_DHCP_HOST_JSON);
    }

    /**
     * Gets URI for this subnet host.
     * @return
     */
    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    /**
     * Gets name of this host.
     * @return
     */
    public String getName() {
        return principalDto.getName();
    }

    /**
     * Gets Ip address of the host.
     * @return
     */
    public String getIpAddr() {
        return principalDto.getIpAddr();
    }

    /**
     *  Gets mac address of the host.
     * @return
     */
    public String getMacAddr() {
        return principalDto.getMacAddr();
    }

    /**
     * Sets name of the host.
     * @param name
     * @return               this
     */
    public SubnetHost name(String name) {
        principalDto.setName(name);
        return this;
    }

    /**
     * Sets mac address of the host.
     * @param macAddr
     * @return                      this
     */
    public SubnetHost macAddr(String macAddr) {
        principalDto.setMacAddr(macAddr);
        return this;
    }

    /**
     * Sets ip address of the host.
     * @param ipAddr
     * @return
     */
    public SubnetHost ipAddr(String ipAddr) {
        principalDto.setIpAddr(ipAddr);
        return this;
    }

    @Override
    public String toString() {
        return String.format("{SubnetHost, ip=%s, mac=%s}", principalDto
                .getIpAddr(), principalDto.getMacAddr());
    }
}


