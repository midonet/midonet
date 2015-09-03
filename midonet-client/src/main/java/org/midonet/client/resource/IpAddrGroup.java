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
import org.midonet.client.dto.DtoIpAddrGroup;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;

import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.*;

public class IpAddrGroup extends ResourceBase<IpAddrGroup, DtoIpAddrGroup> {


    public IpAddrGroup(WebResource resource, URI uriForCreation,
                     DtoIpAddrGroup ipg) {
        super(resource, uriForCreation, ipg, APPLICATION_IP_ADDR_GROUP_JSON());
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

    public IpAddrGroup name(String name) {
        principalDto.setName(name);
        return this;
    }
}
