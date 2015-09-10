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

import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoPort;
import org.midonet.client.dto.DtoPortGroup;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;

import java.net.URI;

/**
 * Abstract port resource class for bridge port and router port
 *
 * @param <T> type of the resource
 * @param <U> type of the dto for the resource
 */
public abstract class Port<T extends Port<T, U>, U extends DtoPort>
    extends ResourceBase<T, U> {

    public Port(WebResource resource, URI uriForCreation,
                U principalDto, String mediaType) {
        super(resource, uriForCreation, principalDto, mediaType);
    }

    public ResourceCollection<PortGroup> getPortGroups() {
        return getChildResources(
                principalDto.getPortGroups(),
                null,
                MidonetMediaTypes.APPLICATION_PORTGROUP_COLLECTION_JSON(),
                PortGroup.class, DtoPortGroup.class);
    }
}

