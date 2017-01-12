/*
 * Copyright 2015 Midokura SARL
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

import java.net.InetAddress;
import java.net.URI;
import java.util.UUID;

import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoInterface;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;

public class HostInterface extends ResourceBase<HostInterface, DtoInterface> {

    public HostInterface(WebResource resource, URI uriForCreation,
                         DtoInterface iface) {
        super(resource, uriForCreation, iface,
              MidonetMediaTypes.APPLICATION_INTERFACE_JSON());
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
