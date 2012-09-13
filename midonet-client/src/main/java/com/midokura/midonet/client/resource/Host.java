/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package com.midokura.midonet.client.resource;

import java.net.URI;
import java.util.UUID;

import com.midokura.midonet.client.VendorMediaType;
import com.midokura.midonet.client.WebResource;
import com.midokura.midonet.client.dto.DtoHost;
import com.midokura.midonet.client.dto.DtoHostInterfacePort;
import com.midokura.midonet.client.dto.DtoInterface;

/**
 * Author: Tomoe Sugihara <tomoe@midokura.com>
 * Date: 9/5/12
 * Time: 10:15 PM
 */
public class Host extends ResourceBase<Host, DtoHost> {

    public Host(WebResource resource, URI uriForCreation, DtoHost host) {
        super(resource, uriForCreation, host,
              VendorMediaType.APPLICATION_HOST_JSON);
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public String[] getAddresses() {
        return principalDto.getAddresses();
    }

    public URI getHostCommands() {
        return principalDto.getHostCommands();
    }

    public UUID getId() {
        return principalDto.getId();
    }

    public String getName() {
        return principalDto.getName();
    }

    public boolean isAlive() {
        return principalDto.isAlive();
    }


    public ResourceCollection<HostInterface> getInterfaces() {
        return getChildResources(
            principalDto.getInterfaces(), null,
            VendorMediaType.APPLICATION_INTERFACE_COLLECTION_JSON,
            HostInterface.class, DtoInterface.class);
    }

    public ResourceCollection<HostInterfacePort> getPorts() {
        return getChildResources(
            principalDto.getPorts(), null,
            VendorMediaType.APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON,
            HostInterfacePort.class, DtoHostInterfacePort.class);
    }

    public HostInterfacePort addHostInterfacePort() {
        return new HostInterfacePort(resource, principalDto.getPorts(),
                                     new DtoHostInterfacePort());
    }
}
