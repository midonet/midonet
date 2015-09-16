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
package org.midonet.cluster.rest_api.serialization;

import java.util.concurrent.ConcurrentHashMap;

import javax.ws.rs.core.MediaType;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationIntrospector;

import org.midonet.util.version.VersionCheckAnnotationIntrospector;

/**
 * Class that providers ObjectMapper construction.
 */
public class ObjectMapperProvider {

    /*
     *  There are two ObjectMapper Map objects to provide two different kinds
     *  of ObjectMapper: one with View configured and one without.  Some models
     *  have their own View defined, and they need to use ObjectMapper with
     *  view configurations.  To decouple views from models, this is the
     *  preferred way, but since legacy models are still not using View,
     *  non-view-configured ObjectMapper map is also available.  It is up to the
     *  resource classes to register media types that should be handled by
     *  View-configured ObjectMappers.
     */
    private static ConcurrentHashMap<Integer, ObjectMapper> mapperMap =
            new ConcurrentHashMap<>();
    private static ConcurrentHashMap<Integer, ObjectMapper> viewMapperMap =
            new ConcurrentHashMap<>();

    private ObjectMapper getMapper(int version, MediaType mediaType) {

        ObjectMapper mapper = new MidonetObjectMapper();
        JacksonAnnotationIntrospector jackIntr =
                new JacksonAnnotationIntrospector();
        JaxbAnnotationIntrospector jaxbIntro =
                new JaxbAnnotationIntrospector(TypeFactory.defaultInstance());
        VersionCheckAnnotationIntrospector introspector =
                new VersionCheckAnnotationIntrospector(
                        Integer.toString(version));

        AnnotationIntrospector defPair =
                AnnotationIntrospector.pair(jackIntr, jaxbIntro);
        AnnotationIntrospector versionPair =
                AnnotationIntrospector.pair(defPair, introspector);

        if (ViewMixinProvider.isRegisteredMediaType(mediaType)) {

            // For this media type, 'View' is implemented, which means we need
            // to construct an ObjectMapper with View configuration.  Once all
            // models are separated from Views, this block becomes the only
            // way to configure ObjectMapper.
            mapper.writerWithView(Views.Public.class);
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                             false)
                  .configure(MapperFeature.DEFAULT_VIEW_INCLUSION, false)
                  .setAnnotationIntrospector(versionPair)
                  .setVisibilityChecker(mapper.getVisibilityChecker()
                      .withFieldVisibility(JsonAutoDetect.Visibility.ANY));
            ViewMixinProvider.setViewMixins(mapper);
        } else {
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                             false)
                  .setAnnotationIntrospector(versionPair)
                  .setVisibilityChecker(mapper.getVisibilityChecker()
                      .withFieldVisibility(JsonAutoDetect.Visibility.ANY));
        }

        return mapper;
    }

    private ConcurrentHashMap<Integer, ObjectMapper> getMapperMap(
            MediaType mediaType) {

        if (ViewMixinProvider.isRegisteredMediaType(mediaType)) {
            return viewMapperMap;
        } else {
            return mapperMap;
        }
    }

    /**
     * Construct ObjectMapper object given the media type and version.
     */
    public ObjectMapper get(int version, MediaType mediaType) {
        ObjectMapper mapper = getMapperMap(mediaType).get(version);
        if (mapper == null) {
            mapper = getMapper(version, mediaType);
            getMapperMap(mediaType).putIfAbsent(version, mapper);
        }
        return mapper;
    }
}
