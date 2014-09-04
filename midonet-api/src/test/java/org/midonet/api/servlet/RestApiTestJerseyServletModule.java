/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.servlet;

import javax.servlet.ServletContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.rest_api.RestApiModule;
import org.midonet.api.vtep.VtepMockableDataClientFactory;
import org.midonet.brain.southbound.vtep.VtepDataClientFactory;

/**
 * Jersey servlet module for MidoNet REST API application.
 */
public class RestApiTestJerseyServletModule extends RestApiJerseyServletModule {

    private final static Logger log = LoggerFactory
            .getLogger(RestApiTestJerseyServletModule.class);

    public RestApiTestJerseyServletModule(ServletContext servletContext) {
        super(servletContext);
    }

    @Override
    protected void installRestApiModule() {
        install(new RestApiModule() {
            protected void bindVtepDataClientFactory() {
                bind(VtepDataClientFactory.class)
                    .to(VtepMockableDataClientFactory.class)
                    .asEagerSingleton();
            }
        });
    }
}
