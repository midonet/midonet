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

import java.util.concurrent.ConcurrentHashMap;

import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.Provider;

import com.google.inject.Inject;

import org.codehaus.jackson.map.ObjectMapper;
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

    private final static Logger log =
            LoggerFactory.getLogger(WildcardJacksonJaxbJsonProvider.class);

    private final VersionParser versionParser = new VersionParser();
    private final ObjectMapperProvider objectMapperProvider;

    @Inject
    public WildcardJacksonJaxbJsonProvider(ObjectMapperProvider omProvider) {
        super();
        this.objectMapperProvider = omProvider;
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

        if (this.objectMapperProvider == null) {
            log.debug("No object mapper available");
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

        log.debug("WildCardJacksonJaxbJsonProvider.locateMapper exiting:" +
                " version=" + version);
        return objectMapperProvider.get(version, mediaType);
    }
}
