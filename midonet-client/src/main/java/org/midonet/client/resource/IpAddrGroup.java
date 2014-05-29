/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */

package org.midonet.client.resource;

import java.net.URI;
import java.util.UUID;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoIpAddrGroup;

public class IpAddrGroup extends ResourceBase<IpAddrGroup, DtoIpAddrGroup> {


    public IpAddrGroup(WebResource resource, URI uriForCreation,
                     DtoIpAddrGroup ipg) {
        super(resource, uriForCreation, ipg,
                VendorMediaType.APPLICATION_IP_ADDR_GROUP_JSON);
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public UUID getId() {
        return principalDto.getId();
    }

    public String getName() {
        return principalDto.getName();
    }

    public IpAddrGroup name(String name) {
        principalDto.setName(name);
        return this;
    }
}
