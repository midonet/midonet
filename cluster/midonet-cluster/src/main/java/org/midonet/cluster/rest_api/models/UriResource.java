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

import com.google.common.base.Preconditions;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonProperty;

import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.rest_api.annotation.Resource;
import org.midonet.cluster.rest_api.annotation.ResourceId;
import org.midonet.cluster.rest_api.annotation.Subresource;

public abstract class UriResource extends ZoomObject {

    protected URI baseUri;
    protected URI uri;

    public UriResource(URI baseUri) {
        this.baseUri = baseUri;
    }

    /** Retrieve the URI of this resource. */
    @JsonProperty("uri")
    final public URI getUri() {
        if (baseUri != null) {
            Resource resource = getResource();
            String id = getId();
            if (null == resource || null == id)
                throw new WebApplicationException(
                    Response.Status.INTERNAL_SERVER_ERROR);
            return UriBuilder.fromUri(baseUri).segment(
                resource.name(), id).build();
        } else {
            return uri;
        }
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    final protected URI getUriFor(String path) {
        Preconditions.checkNotNull(path);
        if (this.uri == null) {
            return null;
        }

        return UriBuilder.fromUri(uri).segment(path).build();
    }

    final protected URI getUriFor(String path, URI defUri) {
        return (uri == null) ? defUri : getUriFor(path);
    }

    final protected URI getUriFor(String path, UUID id) {
        if (id == null || baseUri == null) return null;
        return UriBuilder.fromUri(baseUri).segment(path, id.toString()).build();
    }

    final protected URI getUriFor(String path, UUID id, URI defUri) {
        return (baseUri == null) ? defUri : getUriFor(path, id);
    }

    final protected List<URI> getUrisFor(String path, List<UUID> ids) {
        if (ids == null) return null;
        List<URI> uris = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            uris.add(getUriFor(path, id));
        }
        return uris;
    }

    final protected List<URI> getUrisFor(String path, List<UUID> ids,
                                         List<URI> defUris) {
        return (uri == null) ? defUris : getUrisFor(path, ids);
    }

    final protected String getUriTemplateFor(String path, String defTemplate) {
        return (uri == null) ? defTemplate : uri + path;
    }

    @JsonIgnore
    public void setBaseUri(URI baseUri) throws IllegalAccessException {
        Preconditions.checkNotNull(baseUri);
        this.baseUri = baseUri;
        setSubresourcesUri(getUri());
    }

    @JsonIgnore
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
            for (UriResource resource : list) {
                resource.setBaseUri(uri);
            }
        }
    }

    @JsonIgnore
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

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof UriResource)) return false;
        final UriResource other = (UriResource) obj;

        return Objects.equals(baseUri, other.baseUri)
            && Objects.equals(uri, other.uri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseUri, uri);
    }

    @Override
    public String toString() {
        return com.google.common.base.Objects.toStringHelper(this)
            .add("uri", uri)
            .add("baseUri", baseUri)
            .toString();
    }
}
