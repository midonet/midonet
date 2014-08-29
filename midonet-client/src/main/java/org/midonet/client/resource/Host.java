/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package org.midonet.client.resource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
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
              VendorMediaType.APPLICATION_HOST_JSON_V3);
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public String[] getAddresses() {
        return principalDto.getAddresses();
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

    public List<HostInterface> getHostInterfaces() {
        List<HostInterface> hostInterfaces = new ArrayList<>();
        DtoInterface[] interfaces = principalDto.getHostInterfaces();
        for (DtoInterface intf : interfaces) {
            hostInterfaces.add(new HostInterface(this.resource, null, intf));
        }

        return hostInterfaces;
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
