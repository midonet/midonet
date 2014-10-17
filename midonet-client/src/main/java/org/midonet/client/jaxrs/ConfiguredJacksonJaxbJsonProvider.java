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
package org.midonet.client.jaxrs;

import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.Provider;

import org.codehaus.jackson.jaxrs.Annotations;
import org.codehaus.jackson.jaxrs.JacksonJaxbJsonProvider;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 12/3/11
 */
@Provider
@Consumes({MediaType.APPLICATION_JSON})
@Produces({MediaType.APPLICATION_JSON})
public class ConfiguredJacksonJaxbJsonProvider extends JacksonJaxbJsonProvider {
    public ConfiguredJacksonJaxbJsonProvider() {
        configure();
    }

    public ConfiguredJacksonJaxbJsonProvider(Annotations... annotationsToUse) {
        super(annotationsToUse);

        configure();
    }

    public ConfiguredJacksonJaxbJsonProvider(ObjectMapper mapper,
                                             Annotations[] annotationsToUse) {
        super(mapper, annotationsToUse);

        configure();
    }

    private void configure() {
        configure(DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES,
                false);
    }
}
