/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package org.midonet.client.resource;

import java.net.InetAddress;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoInterface;

/**
 * Author: Tomoe Sugihara <tomoe@midokura.com>
 * Date: 9/6/12
 * Time: 12:07 AM
 */
public class HostInterface extends ResourceBase<HostInterface, DtoInterface> {

    public HostInterface(WebResource resource, URI uriForCreation,
                         DtoInterface iface) {
        super(resource, uriForCreation, iface,
              VendorMediaType.APPLICATION_INTERFACE_JSON);
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public InetAddress[] getAddresses() {
        return principalDto.getAddresses();
    }

    public String getEndpoint() {
        return principalDto.getEndpoint();
    }

    public UUID getHostId() {
        return principalDto.getHostId();
    }

    public UUID getId() {
        return principalDto.getId();
    }

    public String getMac() {
        return principalDto.getMac();
    }

    public int getMtu() {
        return principalDto.getMtu();
    }

    public String getName() {
        return principalDto.getName();
    }

    public String getPortType() {
        return principalDto.getPortType();
    }

    public int getStatus() {
        return principalDto.getStatus();
    }

    public boolean getStatusField(DtoInterface.StatusType statusType) {
        return principalDto.getStatusField(statusType);
    }

    public DtoInterface.Type getType() {
        return principalDto.getType();
    }
}
