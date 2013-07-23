/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.serialization;

import com.google.inject.Inject;
import org.codehaus.jackson.map.ObjectMapper;
import org.midonet.api.version.VersionParser;
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

    private VersionParser versionParser;
    private ObjectMapperProvider objectMapperProvider;

    @Inject
    public WildCardJacksonJaxbJsonProvider(
            ObjectMapperProvider objectMapperProvider,
            VersionParser versionParser) {
        super();
        this.versionParser = versionParser;
        this.objectMapperProvider = objectMapperProvider;
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
        return objectMapperProvider.get(version, mediaType);
    }
}
