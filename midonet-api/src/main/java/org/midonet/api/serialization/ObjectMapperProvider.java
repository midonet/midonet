/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.serialization;

import org.codehaus.jackson.annotate.JsonAutoDetect;
import org.codehaus.jackson.map.AnnotationIntrospector;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.codehaus.jackson.map.introspect.JacksonAnnotationIntrospector;
import org.codehaus.jackson.xc.JaxbAnnotationIntrospector;
import org.midonet.util.version.VersionCheckAnnotationIntrospector;

import javax.ws.rs.core.MediaType;
import java.util.concurrent.ConcurrentHashMap;

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
            new ConcurrentHashMap<Integer, ObjectMapper>();
    private static ConcurrentHashMap<Integer, ObjectMapper> viewMapperMap =
            new ConcurrentHashMap<Integer, ObjectMapper>();

    private ObjectMapper getMapper(int version, MediaType mediaType) {

        ObjectMapper mapper = new ObjectMapper();
        JacksonAnnotationIntrospector jackIntr =
                new JacksonAnnotationIntrospector();
        JaxbAnnotationIntrospector jaxbIntro =
                new JaxbAnnotationIntrospector();
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
            mapper.setSerializationConfig(
                    mapper.getSerializationConfig().withView(
                            Views.Public.class));
            mapper.configure(
                    DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES,
                    false)
                  .configure(
                    SerializationConfig.Feature.DEFAULT_VIEW_INCLUSION,
                    false)
                  .setAnnotationIntrospector(versionPair)
                  .setVisibilityChecker(mapper.getVisibilityChecker()
                      .withFieldVisibility(JsonAutoDetect.Visibility.ANY));
            ViewMixinProvider.setViewMixins(mapper);
        } else {
            mapper.configure(
                    DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES,
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
     *
     * @param version
     * @param mediaType
     * @return
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
