/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */

package org.midonet.client.resource;

import java.net.URI;
import java.util.ArrayList;
import java.util.UUID;

import javax.ws.rs.core.MultivaluedMap;

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.*;

public class WriteVersion extends ResourceBase<WriteVersion, DtoWriteVersion> {


    public WriteVersion(WebResource resource, URI uriForCreation, DtoWriteVersion wv) {
        super(resource, uriForCreation, wv,
              VendorMediaType.APPLICATION_WRITE_VERSION_JSON);
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public String getVersion() {
        return principalDto.getVersion();
    }

    public WriteVersion version(String version) {
        principalDto.setVersion(version);
        return this;
    }

    @Override
    public String toString() {
        return String.format("WriteVersion{version=%s}",
                principalDto.getVersion());
    }
}
