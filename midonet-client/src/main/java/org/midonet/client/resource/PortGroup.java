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

import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoPortGroup;

import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_PORTGROUP_JSON;

public class PortGroup extends ResourceBase<PortGroup, DtoPortGroup> {


    public PortGroup(WebResource resource, URI uriForCreation,
                     DtoPortGroup pg) {
        super(resource, uriForCreation, pg, APPLICATION_PORTGROUP_JSON());
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public UUID getId() {
        return principalDto.getId();
    }

    public String getName() {
        return principalDto.getName();
    }

    public String getTenantId() {
        return principalDto.getTenantId();
    }

    public boolean isStateful() {
        return principalDto.isStateful();
    }

    public PortGroup name(String name) {
        principalDto.setName(name);
        return this;
    }

    public PortGroup tenantId(String tenantId) {
        principalDto.setTenantId(tenantId);
        return this;
    }

    public PortGroup stateful(boolean stateful) {
        principalDto.setStateful(stateful);
        return this;
    }

}
