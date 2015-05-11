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

import com.google.protobuf.Message;

import org.codehaus.jackson.annotate.JsonIgnore;

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

    final public void setBaseUri(URI baseUri) throws IllegalAccessException {
        this.baseUri = baseUri;
    }

    @JsonIgnore
    final public Class<? extends Message> getZoomClass() {
        return getZoomClass(getClass());
    }

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

    /*

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
    }*/
}
