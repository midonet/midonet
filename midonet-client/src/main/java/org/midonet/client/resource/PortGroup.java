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
import org.midonet.client.dto.DtoPortGroup;

public class PortGroup extends ResourceBase<PortGroup, DtoPortGroup> {


    public PortGroup(WebResource resource, URI uriForCreation,
                     DtoPortGroup pg) {
        super(resource, uriForCreation, pg, VendorMediaType.APPLICATION_PORTGROUP_JSON);
    }

    /**
     * Gets URI for this port groups
     *
     * @return URI of this port group
     */
    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    /**
     * Gets ID of the port Groups
     *
     * @return UUID of this port groups
     */
    public UUID getId() {
        return principalDto.getId();
    }

    /**
     * Gets name of this port group
     *
     * @return name of this port group
     */
    public String getName() {
        return principalDto.getName();
    }

    /**
     * Gets tenant ID string of this port group
     *
     * @return tenant ID string
     */
    public String getTenantId() {
        return principalDto.getTenantId();
    }

    /**
     * Gets stateful flag
     *
     * @return stateful flag
     */
    public boolean isStateful() {
        return principalDto.isStateful();
    }

    /**
     * Sets name of this portgroup for creation
     *
     * @param name name of the port group
     * @return this
     */
    public PortGroup name(String name) {
        principalDto.setName(name);
        return this;
    }

    /**
     * Sets tenantId.
     *
     * @param tenantId tenant ID
     * @return this
     */
    public PortGroup tenantId(String tenantId) {
        principalDto.setTenantId(tenantId);
        return this;
    }

    /**
     * Sets stateful flag.
     *
     * @param stateful
     * @return this
     */
    public PortGroup stateful(boolean stateful) {
        principalDto.setStateful(stateful);
        return this;
    }

}
