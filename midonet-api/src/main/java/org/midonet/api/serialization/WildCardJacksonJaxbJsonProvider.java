/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.serialization;

import com.google.inject.Inject;
import org.codehaus.jackson.annotate.JsonAutoDetect;
import org.codehaus.jackson.map.AnnotationIntrospector;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.codehaus.jackson.map.introspect.JacksonAnnotationIntrospector;
import org.codehaus.jackson.xc.JaxbAnnotationIntrospector;
import org.midonet.api.version.VersionParser;
import org.midonet.util.version.VersionCheckAnnotationIntrospector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.Provider;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Custom JacksonJaxbJsonProvider that can handle vendor media types.  This
 * class also utilizes {link @ObjectMapperFactory} to construct different
 * {@link ObjectMapper} objects for each media type version number.
 */
@Provider
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.WILDCARD)
public class WildCardJacksonJaxbJsonProvider
        extends ConfiguredJacksonJaxbJsonProvider {

    private final static Logger log =
            LoggerFactory.getLogger(WildCardJacksonJaxbJsonProvider.class);

    private static ConcurrentHashMap<Integer, ObjectMapper> mapperMap =
            new ConcurrentHashMap<Integer, ObjectMapper>();

    private VersionParser versionParser;

    @Inject
    public WildCardJacksonJaxbJsonProvider(VersionParser versionParser) {
        super();
        this.versionParser = versionParser;
    }

    private ObjectMapper getMapper(int version, Class<?> type,
                                         MediaType mediaType) {

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

        mapper.configure(DeserializationConfig.Feature
                .FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setAnnotationIntrospector(versionPair)
                .setVisibilityChecker(mapper.getVisibilityChecker()
                        .withFieldVisibility(
                                JsonAutoDetect.Visibility.ANY));

        return mapper;
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
        log.debug("WildCardJacksonJaxbJsonProvider.locateMapper entered: " +
                "media type=" + mediaType);

        int version = versionParser.getVersion(mediaType);
        if (version <= 0) {
            // No version was provided in media type.  Just default to version
            // 1 for backward compatibility but this must change to either
            // get the latest available version for the requested object,
            // or just not handle this case.
            version = 1;
        }

        log.debug("WildCardJacksonJaxbJsonProvider.locateMapper exiting:" +
                " version=" + version);
        ObjectMapper mapper = mapperMap.get(version);
        if (mapper == null) {
            mapper = getMapper(version, type, mediaType);
            mapperMap.putIfAbsent(version, mapper);
        }
        return mapper;
    }
}
