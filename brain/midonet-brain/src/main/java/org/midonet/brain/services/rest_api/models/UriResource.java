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

package org.midonet.brain.services.rest_api.models;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;

import org.midonet.brain.services.rest_api.annotation.Resource;
import org.midonet.brain.services.rest_api.annotation.ResourceId;
import org.midonet.cluster.data.ZoomObject;

public abstract class UriResource extends ZoomObject {

    private URI baseUri = null;

    @XmlTransient
    public void setBaseUri(URI baseUri) {
        this.baseUri = baseUri;
    }

    /**
     * Retrieve the URI of this resource.
     */
    @XmlElement(name = "uri")
    final public URI getUri() {
        return UriBuilder.fromUri(baseUri).segment(getPath(), getId()).build();
    }

    @XmlTransient
    final protected URI getUriFor(String path) {
        return UriBuilder.fromUri(getUri()).segment(path).build();
    }

    @XmlTransient
    final protected URI getUriFor(String path, UUID id) {
        return UriBuilder.fromUri(baseUri).segment(path, id.toString()).build();
    }

    @XmlTransient
    final protected List<URI> getUrisFor(String path, List<UUID> ids) {
        List<URI> uris = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            uris.add(getUriFor(path, id));
        }
        return uris;
    }

    @XmlTransient
    private String getId() {
        try {
            return getIdField(getClass()).get(this).toString();
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    @XmlTransient
    public void setId(UUID id) throws IllegalAccessException {
        getIdField(getClass()).set(this, id);
    }

    @XmlTransient
    private String getPath() {
        return getPath(getClass());
    }

    private static Field getIdField(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.getAnnotation(ResourceId.class) != null) {
                return field;
            }
        }
        if (UriResource.class.isAssignableFrom(clazz.getSuperclass())) {
            return getIdField(clazz.getSuperclass());
        } else {
            throw new RuntimeException();
        }
    }

    private static String getPath(Class<?> clazz) {
        Resource resource = clazz.getAnnotation(Resource.class);
        if (null != resource) {
            return resource.path();
        } else if (UriResource.class.isAssignableFrom(clazz.getSuperclass())) {
            return getPath(clazz.getSuperclass());
        } else  {
            throw new RuntimeException();
        }
    }

}
