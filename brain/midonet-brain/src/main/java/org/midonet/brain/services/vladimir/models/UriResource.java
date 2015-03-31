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

package org.midonet.brain.services.vladimir.models;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;

import org.midonet.cluster.data.ZoomObject;

public abstract class UriResource extends ZoomObject {

    private URI baseUri = null;

    @XmlTransient
    public void setBaseUri(URI baseUri) {
        this.baseUri = baseUri;
    }

    protected URI uriFor(String s) {
        return UriBuilder.fromUri(baseUri).path(s).build();
    }

    @XmlTransient
    protected List<URI> toUris(String prefix, List<UUID> ids) {
        if (ids == null) {
            return new ArrayList<>(0);
        }
        List<URI> uris = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            uris.add(buildUri(prefix, id));
        }
        return uris;
    }

    /**
     * Retrieve the URI of this resource.
     */
    @XmlElement(name = "uri")
    abstract public String getUri();

    /**
     * Builds the URI for the given sub resource node.
     */
    @XmlTransient
    protected URI buildUri(String node) {
        // TODO: make this properly
        return UriBuilder.fromUri(getUri()).path(node).build();
    }

    @XmlTransient
    private URI buildUri(String node, UUID id) {
        // TODO: make this properly
        return UriBuilder.fromUri(getUri()).segment(node)
            .segment(id.toString()).build();
    }

}
