/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.client.resource;

import java.net.URI;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.*;

public class SystemState extends ResourceBase<SystemState, DtoSystemState> {


    public SystemState(WebResource resource, URI uriForCreation, DtoSystemState ss) {
        super(resource, uriForCreation, ss,
              VendorMediaType.APPLICATION_SYSTEM_STATE_JSON);
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public String getState() {
        return principalDto.getState();
    }

    public SystemState setState(String state) {
        principalDto.setState(state);
        return this;
    }

    public String getAvailability() {
        return principalDto.getAvailability();
    }

    public SystemState setAvailability(String availability) {
        principalDto.setAvailability(availability);
        return this;
    }

    @Override
    public String toString() {
        return String.format("SystemState{state=%s, availability=%s}",
                principalDto.getState(), principalDto.getAvailability());
    }
}
