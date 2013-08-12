package org.midonet.client.resource;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoMacPort;

import java.net.URI;
import java.util.UUID;

public class MacPort extends ResourceBase<MacPort, DtoMacPort> {

    public MacPort(WebResource resource, URI uriForCreation, DtoMacPort mp) {
        super(resource, uriForCreation, mp,
                VendorMediaType.APPLICATION_MAC_PORT_JSON_V2);
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public Short getVlanId() {
        return principalDto.getVlanId();
    }
    public String getMacAddr() {
        return principalDto.getMacAddr();
    }

    public UUID getPortId() {
        return principalDto.getPortId();
    }

    @Override
    public String toString() {
        // String.format("Route{id=%s}", principalDto.getId());
        return String.format("MacPort{vlanId=%d, macAddr=%s, portId=%s}",
                getVlanId(), getMacAddr(), getPortId());
    }
}
