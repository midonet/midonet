/*
 * Copyright (c) 2013. Midokura Japan K.K.
 */
package org.midonet.client.resource;

import java.net.URI;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoDhcpV6Host;

public class DhcpV6Host extends ResourceBase<DhcpV6Host, DtoDhcpV6Host> {

    public DhcpV6Host(WebResource resource, URI uriForCreation, DtoDhcpV6Host
        principalDto) {
        super(resource, uriForCreation, principalDto,
                VendorMediaType.APPLICATION_DHCPV6_HOST_JSON);
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public String getName() {
        return principalDto.getName();
    }

    public String getFixedAddress() {
        return principalDto.getFixedAddress();
    }

    public String getClientId() {
        return principalDto.getClientId();
    }

    public DhcpV6Host name(String name) {
        principalDto.setName(name);
        return this;
    }

    public DhcpV6Host clientId(String clientId) {
        principalDto.setClientId(clientId);
        return this;
    }

    public DhcpV6Host fixedAddress(String fixedAddress) {
        principalDto.setFixedAddress(fixedAddress);
        return this;
    }

    @Override
    public String toString() {
        return String.format("{DhcpV6Host, fixedAddress=%s, clientId=%s}", principalDto
                .getFixedAddress(), principalDto.getClientId());
    }
}


