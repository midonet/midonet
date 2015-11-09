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

package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.UUID;

import javax.validation.constraints.NotNull;

import org.midonet.cluster.rest_api.ResourceUris;

public class Link extends UriResource {

    // TODO: @IsValidPortId
    @NotNull
    public UUID portId;

    // TODO: @IsValidPortId
    @NotNull
    public UUID peerId;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.PORTS, portId, ResourceUris.LINK);
    }

    public URI getPort() {
        return absoluteUri(ResourceUris.PORTS, portId);
    }

    public URI getPeer() {
        return absoluteUri(ResourceUris.PORTS, peerId);
    }

}
