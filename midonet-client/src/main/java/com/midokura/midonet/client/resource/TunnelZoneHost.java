/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package com.midokura.midonet.client.resource;

import java.net.URI;
import java.util.UUID;

import com.midokura.midonet.client.VendorMediaType;
import com.midokura.midonet.client.WebResource;
import com.midokura.midonet.client.dto.DtoTunnelZoneHost;

/**
 * Author: Tomoe Sugihara <tomoe@midokura.com>
 * Date: 9/18/12
 * Time: 11:02 PM
 */
public class TunnelZoneHost extends ResourceBase<TunnelZoneHost,
    DtoTunnelZoneHost> {

    public TunnelZoneHost(WebResource resource, URI uriForCreation,
                          DtoTunnelZoneHost tZoneHost, String vendorMediaType) {
        super(resource, uriForCreation, tZoneHost, vendorMediaType);
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public UUID getTunnelZoneId() {
        return principalDto.getTunnelZoneId();
    }

    public UUID getHostId() {
        return principalDto.getHostId();
    }

    public String getIpAddress() {
        return principalDto.getIpAddress();
    }

    public TunnelZoneHost ipAddress(String ipAddress) {
        principalDto.setIpAddress(ipAddress);
        return this;
    }

    public TunnelZoneHost hostId(UUID hostId) {
        principalDto.setHostId(hostId);
        return this;
    }
}
