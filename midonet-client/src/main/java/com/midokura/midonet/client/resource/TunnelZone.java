/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package com.midokura.midonet.client.resource;

import java.net.URI;
import java.util.UUID;

import com.midokura.midonet.client.VendorMediaType;
import com.midokura.midonet.client.WebResource;
import com.midokura.midonet.client.dto.DtoTunnelZone;
import com.midokura.midonet.client.dto.DtoTunnelZoneHost;

/**
 * Author: Tomoe Sugihara <tomoe@midokura.com>
 * Date: 9/13/12
 * Time: 11:09 PM
 */
public class TunnelZone<T extends DtoTunnelZone>
    extends ResourceBase<TunnelZone, T> {
    private String tunnelZoneHostMediaType = null;
    private String tunnelZoneHostListMediaType = null;


    public TunnelZone(WebResource resource, URI uriForCreation,
                      T tunnelZone, String tunnelZoneHostMediaType,
                                    String tunnelZoneHostListMediaType) {
        super(resource, uriForCreation, tunnelZone,
              VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON);
        this.tunnelZoneHostMediaType = tunnelZoneHostMediaType;
        this.tunnelZoneHostListMediaType = tunnelZoneHostListMediaType;

    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public String getType() {
        return principalDto.getType();
    }

    public String getName() {
        return principalDto.getName();
    }

    public UUID getId() {
        return principalDto.getId();
    }

    public TunnelZone<T> name(String name) {
        principalDto.setName(name);
        return this;
    }

    public ResourceCollection<TunnelZoneHost> getHosts() {
        return getChildResources(
            principalDto.getHosts(), null,
            tunnelZoneHostListMediaType,
            TunnelZoneHost.class, DtoTunnelZoneHost.class);
    }

    public TunnelZoneHost addTunnelZoneHost() {
        return new TunnelZoneHost(resource, principalDto.getHosts(),
                            new DtoTunnelZoneHost(), tunnelZoneHostMediaType);
    }
}
