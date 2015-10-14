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

import java.net.URI;

import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoPortGroup;
import org.midonet.client.dto.DtoRouter;
import org.midonet.client.dto.DtoRuleChain;

import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_BRIDGE_COLLECTION_JSON_V4;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_CHAIN_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_PORTGROUP_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_ROUTER_COLLECTION_JSON_V3;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TENANT_JSON_V2;

public class Tenant extends ResourceBase<Tenant,
    org.midonet.cluster.rest_api.models.Tenant> {

    public Tenant(WebResource resource, URI uriForCreation,
                  org.midonet.cluster.rest_api.models.Tenant t) {
        super(resource, uriForCreation, t, APPLICATION_TENANT_JSON_V2());
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public String getId() {
        return principalDto.id;
    }

    public String getName() {
        return principalDto.name;
    }

    public Tenant name(String name) {
        principalDto.name = name;
        return this;
    }

    public ResourceCollection<Bridge> getBridges() {
        return getChildResources(
            principalDto.getBridges(),
            null,
            APPLICATION_BRIDGE_COLLECTION_JSON_V4(),
            Bridge.class, DtoBridge.class);
    }

    public ResourceCollection<PortGroup> getPortGroups() {
        return getChildResources(
                principalDto.getPortGroups(),
                null,
                APPLICATION_PORTGROUP_COLLECTION_JSON(),
                PortGroup.class, DtoPortGroup.class);
    }

    public ResourceCollection<Router> getRouters() {
        return getChildResources(
                principalDto.getRouters(),
                null,
                APPLICATION_ROUTER_COLLECTION_JSON_V3(),
                Router.class, DtoRouter.class);
    }

    public ResourceCollection<RuleChain> getRuleChains() {
        return getChildResources(
                principalDto.getChains(),
                null,
                APPLICATION_CHAIN_COLLECTION_JSON(),
                RuleChain.class, DtoRuleChain.class);
    }


    @Override
    public String toString() {
        return String.format("Tenant{id=%s, name=%s}", principalDto.id,
                             principalDto.name);
    }
}
