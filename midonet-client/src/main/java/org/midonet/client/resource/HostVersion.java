/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
