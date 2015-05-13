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
package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.UUID;

import javax.validation.constraints.NotNull;

/**
 * Note that this resource doesn't have a counter part in the new storage
 * model and will be deleted.
 */
@Deprecated
public class PortGroupPort extends UriResource {

    @NotNull
    public UUID portGroupId;

    @NotNull
    public UUID portId;

    public URI getPort() {
        return getUriFor(ResourceUris.PORTS, portId);
    }

    public URI getPortGroup() {
        return getUriFor(ResourceUris.PORT_GROUPS, portGroupId);
    }

}
