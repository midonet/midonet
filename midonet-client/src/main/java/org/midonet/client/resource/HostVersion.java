/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */

package org.midonet.client.resource;

import java.net.URI;
import java.util.UUID;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoHostVersion;

public class HostVersion extends ResourceBase<HostVersion, DtoHostVersion> {


    public HostVersion(WebResource resource, URI uriForCreation, DtoHostVersion hv) {
        super(resource, uriForCreation, hv,
              VendorMediaType.APPLICATION_HOST_VERSION_JSON);
    }

    @Override
    public URI getUri() {
        return null;
    }

    public String getVersion() {
        return principalDto.getVersion();
    }

    public HostVersion setVersion(String version) {
        principalDto.setVersion(version);
        return this;
    }

    public URI getHost() {
        return principalDto.getHost();
    }

    public HostVersion setHost(URI host) {
        principalDto.setHost(host);
        return this;
    }

    public UUID getHostId() {
        return principalDto.getHostId();
    }

    public HostVersion setHostId(UUID hostId) {
        principalDto.setHostId(hostId);
        return this;
    }

    @Override
    public String toString() {
        return String.format("HostVersion{version=%s,host=%s,hostId=%s}",
                principalDto.getVersion(),
                principalDto.getHost(),
                principalDto.getHostId());
    }
}
