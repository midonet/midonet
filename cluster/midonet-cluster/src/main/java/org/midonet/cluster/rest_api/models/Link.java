package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.UUID;

public class Link extends UriResource {

    // TODO: @IsValidPortId
    public UUID portId;

    // TODO: @IsValidPortId
    public UUID peerId;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.PORTS, portId, ResourceUris.LINK);
    }

    public URI getPort() {
        return absoluteUri(ResourceUris.PORTS, portId);
    }

    public URI getPeer() {
        return absoluteUri(ResourceUris.PORTS, peerId);
    }

}
