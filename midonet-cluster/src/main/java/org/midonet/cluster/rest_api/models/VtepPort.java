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
import java.net.URLEncoder;
import java.util.UUID;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.rest_api.ResourceUris;

public class VtepPort extends UriResource {

    @NotNull
    public String name;

    @NotNull
    @JsonIgnore
    public String uriName;

    public String description;

    @NotNull
    @JsonIgnore
    public UUID vtepId;

    public VtepPort() {}

    public VtepPort(URI baseUri, String name, String uriName,
                    String description, UUID vtepId) {
        setBaseUri(baseUri);
        this.name = name;
        this.uriName = uriName;
        this.description = description;
        this.vtepId = vtepId;
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.VTEPS(), vtepId,
                           ResourceUris.PORTS(), uriName);
    }

    public URI getVtep() {
        return absoluteUri(ResourceUris.VTEPS(), vtepId);
    }

    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("name", name)
            .add("description", description)
            .toString();
    }
}
