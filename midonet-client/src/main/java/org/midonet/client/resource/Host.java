/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package org.midonet.client.resource;

import java.net.URI;
import java.util.UUID;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoHost;
import org.midonet.client.dto.DtoHostInterfacePort;
import org.midonet.client.dto.DtoInterface;

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

    public Integer getFloodingProxyWeight() {
        return principalDto.getFloodingProxyWeight();
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
