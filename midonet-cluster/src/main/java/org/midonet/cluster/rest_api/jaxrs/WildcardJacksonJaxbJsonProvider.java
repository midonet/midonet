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
package org.midonet.cluster.rest_api.jaxrs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.Provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.rest_api.serialization.ObjectMapperProvider;
import org.midonet.cluster.rest_api.version.VersionParser;

/**
 * Custom JacksonJaxbJsonProvider that can handle vendor media types.  This
 * class also utilizes {link @ObjectMapperFactory} to construct different
 * {@link ObjectMapper} objects for each media type version number.
 */
@Provider
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.WILDCARD)
public class WildcardJacksonJaxbJsonProvider
    extends ConfiguredJacksonJaxbJsonProvider {

    private @interface Version {
        int value();
    }

    private final static Logger log =
        LoggerFactory.getLogger(WildcardJacksonJaxbJsonProvider.class);

    private final VersionParser versionParser = new VersionParser();
    private final ObjectMapperProvider objectMapperProvider;
    private final Map<Integer, Version> versionAnnotations =
        new ConcurrentHashMap<>();

    @Inject
    public WildcardJacksonJaxbJsonProvider(ObjectMapperProvider omProvider) {
        super();
        this.objectMapperProvider = omProvider;
        this._cfgCheckCanSerialize = true;
        this._cfgCheckCanDeserialize = true;
    }

    /**
     * Overrides to locate {@link ObjectMapper} object from a static
     * {@link ConcurrentHashMap} class based on the version extracted from
     * the media type.
     *
     * @param type Class of object being serialized or deserialized.
     * @param mediaType Declared media type for the instance to process.
     */
    @Override
    public ObjectMapper locateMapper(Class<?> type, MediaType mediaType) {
        if (this.objectMapperProvider == null) {
            log.debug("No object mapper available for class {} and media " +
                      "type {}", type, mediaType);
            return super.locateMapper(type, mediaType);
        }

        int version = versionParser.getVersion(mediaType);
        if (version <= 0) {
            // No version was provided in media type.  Just default to version
            // 1 for backward compatibility but this must change to either
            // get the latest available version for the requested object,
            // or just not handle this case.
            version = 1;
        }
        return objectMapperProvider.get(version);
    }

    /**
     * Overrides the {@link com.fasterxml.jackson.jaxrs.base.ProviderBase}
     * read method, in order to add a custom {@link Version} annotation to the
     * annotations set.
     *
     * The provider uses a map to cache JSON endpoint reader configurations,
     * where the map key is the Java object class and an array of annotations.
     * Because the provider does not use the media type, but rather this class
     * based key, to get cached reader, by adding this custom version annotation
     * we ensure that cached readers are differentiated by version.
     */
    @Override
    public Object readFrom(Class<Object> type, Type genericType,
                           Annotation[] annotations, MediaType mediaType,
                           MultivaluedMap<String,String> httpHeaders,
                           InputStream entityStream) throws IOException {
        int version = versionParser.getVersion(mediaType);
        if (version <= 0) {
            version = 1;
        }

        Annotation[] annotationsWithVer =
            Arrays.copyOf(annotations, annotations.length + 1);
        annotationsWithVer[annotations.length] = getVersionAnnotation(version);

        return super.readFrom(type, genericType, annotationsWithVer, mediaType,
                              httpHeaders, entityStream);
    }

    /**
     * Overrides the {@link com.fasterxml.jackson.jaxrs.base.ProviderBase}
     * write method, in order to add a custom {@link Version} annotation to the
     * annotations set.
     *
     * The provider uses a map to cache JSON endpoint writer configurations,
     * where the map key is the Java object class and an array of annotations.
     * Because the provider does not use the media type, but rather this class
     * based key, to get cached writer, by adding this custom version annotation
     * we ensure that cached writers are differentiated by version.
     */
    @Override
    public void writeTo(Object value, Class<?> type, Type genericType,
                        Annotation[] annotations, MediaType mediaType,
                        MultivaluedMap<String,Object> httpHeaders,
                        OutputStream entityStream)
        throws IOException {

        int version = versionParser.getVersion(mediaType);
        if (version <= 0) {
            version = 1;
        }

        Annotation[] annotationsWithVer =
            Arrays.copyOf(annotations, annotations.length + 1);
        annotationsWithVer[annotations.length] = getVersionAnnotation(version);

        super.writeTo(value, type, genericType, annotationsWithVer, mediaType,
                      httpHeaders, entityStream);
    }

    private Version getVersionAnnotation(final int version) {
        Version annotation = versionAnnotations.get(version);
        if (null == annotation) {
            annotation = new Version() {
                @Override
                public int value() { return version; }
                public Class<? extends Annotation> annotationType() {
                    return Version.class;
                }
            };
            versionAnnotations.put(version, annotation);
        }
        return annotation;
    }

}
