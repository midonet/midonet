/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.rest_api.jaxrs;

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
