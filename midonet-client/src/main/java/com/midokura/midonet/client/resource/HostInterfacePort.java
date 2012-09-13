/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package com.midokura.midonet.client.resource;

import java.net.URI;
import java.util.UUID;

import com.midokura.midonet.client.VendorMediaType;
import com.midokura.midonet.client.WebResource;
import com.midokura.midonet.client.dto.DtoHostInterfacePort;

/**
 * Author: Tomoe Sugihara <tomoe@midokura.com>
 * Date: 9/13/12
 * Time: 9:39 PM
 */
public class HostInterfacePort
    extends ResourceBase<HostInterfacePort, DtoHostInterfacePort> {

    public HostInterfacePort(WebResource resource, URI uriForCreation,
                             DtoHostInterfacePort interfacePort) {
        super(resource, uriForCreation, interfacePort,
              VendorMediaType.APPLICATION_HOST_INTERFACE_PORT_JSON);
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public UUID getHostId() {
        return principalDto.getHostId();
    }

    public String getInterfaceName() {
        return principalDto.getInterfaceName();
    }

    public UUID getPortId() {
        return principalDto.getPortId();
    }

    public HostInterfacePort portId(UUID id) {
        principalDto.setPortId(id);
        return this;
    }

    public HostInterfacePort interfaceName(String name) {
        principalDto.setInterfaceName(name);
        return this;
    }
}
