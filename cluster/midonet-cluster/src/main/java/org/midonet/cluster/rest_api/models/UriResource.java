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

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;

import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.rest_api.annotation.Resource;
import org.midonet.cluster.rest_api.annotation.ResourceId;
import org.midonet.cluster.rest_api.annotation.Subresource;

public abstract class UriResource extends ZoomObject {

    private URI baseUri = null;

    /** Retrieve the URI of this resource. */
    @XmlElement(name = "uri")
    final public URI getUri() {
        Resource resource = getResource();
        String id = getId();
        if (null == resource || null == id)
            throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
        return UriBuilder.fromUri(baseUri).segment(resource.name(), id).build();
    }

    /** Gets an URI for the specified path relative to the URI of the current
     * object. */
    final protected URI relativeUri(String path) {
        return UriBuilder.fromUri(getUri()).segment(path).build();
    }

    /** Gets an URI for the specified path and identifier, relative to the base
     * URI of the current object. */
    final protected URI absoluteUri(String path, UUID id) {
        if (id == null) return null;
        return UriBuilder.fromUri(baseUri).segment(path, id.toString()).build();
    }

    final protected List<URI> absoluteUris(String path, List<UUID> ids) {
        if (ids == null) return null;
        List<URI> uris = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            uris.add(absoluteUri(path, id));
        }
        return uris;
    }

    @XmlTransient
    public void setBaseUri(URI baseUri) throws IllegalAccessException {
        this.baseUri = baseUri;
        setSubresourcesUri(getUri());
    }

    @XmlTransient
    @Nullable
    final public String getId() {
        Field field = getField(getClass(), ResourceId.class);
        if (null == field) return null;
        try {
            return Objects.toString(field.get(this), null);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private void setSubresourcesUri(URI uri) throws IllegalAccessException {
        Class<?> c = getClass();
        do {
            for (Field field : c.getDeclaredFields()) {
                if (field.getAnnotation(Subresource.class) == null) continue;
                setFieldUri(field, uri);
            }
            c = c.getSuperclass();
        } while (UriResource.class.isAssignableFrom(c.getSuperclass()));
    }

    @SuppressWarnings("unchecked")
    private void setFieldUri(Field field, URI uri)
        throws IllegalAccessException {
        if (isUriResource(field.getType())) {
            ((UriResource)field.get(this)).setBaseUri(uri);
        } else if (isUriResourceArray(field.getType())) {
            Object array = field.get(this);
            for (int index = 0; index < Array.getLength(array); index++) {
                ((UriResource)Array.get(array, index)).setBaseUri(uri);
            }
        } else if (isUriResourceList(field.getGenericType())) {
            List<UriResource> list = (List<UriResource>) field.get(this);
            if (list == null) {
                return;
            }
            for (UriResource resource : list) {
                resource.setBaseUri(uri);
            }
        }
    }

    @XmlTransient
    @Nullable
    final public Resource getResource() {
        return getResource(getClass());
    }

    private static Field getField(Class<?> clazz,
                                  Class<? extends Annotation> annotationClass) {
        Class<?> c = clazz;
        do {
            for (Field field : c.getDeclaredFields()) {
                if (field.getAnnotation(annotationClass) != null) {
                    return field;
                }
            }
            c = c.getSuperclass();
        } while (UriResource.class.isAssignableFrom(c.getSuperclass()));
        return null;
    }

    private static Resource getResource(Class<?> clazz) {
        Class<?> c = clazz;
        do {
            Resource resource = c.getAnnotation(Resource.class);
            if (null != resource) {
                return resource;
            }
            c = c.getSuperclass();
        } while (UriResource.class.isAssignableFrom(c.getSuperclass()));
        return null;
    }

    private static Boolean isUriResource(Class<?> clazz) {
        return UriResource.class.isAssignableFrom(clazz);
    }

    private static Boolean isUriResourceArray(Class<?> clazz) {
        return clazz.isArray() &&
               UriResource.class.isAssignableFrom(clazz.getComponentType());
    }

    private static Boolean isUriResourceList(Type type) {
        ParameterizedType paramType = (ParameterizedType)type;
        return (null != paramType) &&
               List.class.isAssignableFrom((Class<?>)paramType.getRawType()) &&
               UriResource.class.isAssignableFrom(
                   (Class<?>)paramType.getActualTypeArguments()[0]);
    }
}
