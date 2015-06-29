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
import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.protobuf.Message;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomObject;

public abstract class UriResource extends ZoomObject {

    private URI baseUri = null;

    /** Retrieve the URI of this resource. */
    public abstract URI getUri();

    /** Gets an URI for the specified path and identifier, relative to the base
      * URI of the current object. */
    final protected URI absoluteUri(String path, Object id) {
        if (id == null) return null;
        return UriBuilder.fromUri(baseUri).segment(path, id.toString()).build();
    }

    /** Gets an URI for the specified path and identifier, relative to the base
     * URI of the current object. */
    final protected URI absoluteUri(String path1, Object id, String path2) {
        if (id == null) return null;
        return UriBuilder.fromUri(baseUri).segment(path1, id.toString(), path2)
            .build();
    }

    /** Gets an URI for the specified path and identifier, relative to the base
     * URI of the current object. */
    final protected URI absoluteUri(String path1, Object id1, String path2,
                                    Object id2) {
        if (id1 == null || id2 == null) return null;
        return UriBuilder.fromUri(baseUri)
            .segment(path1, id1.toString(), path2, id2.toString())
            .build();
    }

    final protected List<URI> absoluteUris(String path, List<?> ids) {
        if (ids == null) return null;
        List<URI> uris = new ArrayList<>(ids.size());
        for (Object id : ids) {
            uris.add(absoluteUri(path, id));
        }
        return uris;
    }

    /** Gets an URI for the specified path relative to the URI of the current
     * object. */
    final protected URI relativeUri(String path) {
        return UriBuilder.fromUri(getUri()).segment(path).build();
    }

    @JsonIgnore
    final public void setBaseUri(URI baseUri) {
        this.baseUri = baseUri;
    }

    @JsonIgnore
    final public Class<? extends Message> getZoomClass() {
        return getZoomClass(getClass());
    }

    public void create() { }

    public static Class<? extends Message> getZoomClass(Class<?> clazz) {
        Class<?> c = clazz;
        while (UriResource.class.isAssignableFrom(c)) {
            ZoomClass zoomClass = c.getAnnotation(ZoomClass.class);
            if (null != zoomClass) {
                return zoomClass.clazz();
            }
            c = c.getSuperclass();
        }
        throw new WebApplicationException(Response.Status.NOT_ACCEPTABLE);
    }

}
