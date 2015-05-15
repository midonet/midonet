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
import java.util.List;
import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.rest_api.annotation.Resource;
import org.midonet.cluster.rest_api.annotation.ResourceId;
import org.midonet.cluster.rest_api.annotation.Subresource;

@XmlRootElement
@Resource(name = ResourceUris.PORT_GROUPS)
public class PortGroup extends UriResource {

    public static final int MIN_PORT_GROUP_NAME_LEN = 1;
    public static final int MAX_PORT_GROUP_NAME_LEN = 255;

    @ResourceId
    public UUID id;

    @NotNull
    public String tenantId;

    @NotNull
    @Size(min = MIN_PORT_GROUP_NAME_LEN, max = MAX_PORT_GROUP_NAME_LEN)
    public String name;

    public boolean stateful;

    public URI getPorts() {
        return relativeUri(ResourceUris.PORTS);
    }


}

