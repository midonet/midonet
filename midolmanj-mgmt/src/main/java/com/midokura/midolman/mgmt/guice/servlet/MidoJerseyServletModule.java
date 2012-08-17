/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.servlet;

import com.midokura.midolman.mgmt.auth.AuthContainerRequestFilter;
import com.midokura.midolman.mgmt.guice.auth.AuthModule;
import com.midokura.midolman.mgmt.guice.config.ConfigurationModule;
import com.midokura.midolman.mgmt.guice.data.DaoModule;
import com.midokura.midolman.mgmt.guice.data.DataStoreModule;
import com.midokura.midolman.mgmt.guice.jaxrs.JaxrsModule;
import com.midokura.midolman.mgmt.guice.jaxrs.validation.ValidationModule;
import com.midokura.midolman.mgmt.guice.rest_api.RestApiModule;
import com.midokura.midolman.mgmt.rest_api.resources.ExceptionFilter;
import com.midokura.midolman.mgmt.servlet.filter.AuthFilter;
import com.midokura.midolman.mgmt.servlet.filter.CrossOriginResourceSharingFilter;
import com.sun.jersey.api.container.filter.RolesAllowedResourceFilterFactory;
import com.sun.jersey.api.core.ResourceConfig;
import com.sun.jersey.guice.JerseyServletModule;
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import java.util.HashMap;
import java.util.Map;

/**
 * Jersey servlet module for Midolman API application.
 */
public class MidoJerseyServletModule extends JerseyServletModule {

    private final static Logger log = LoggerFactory
            .getLogger(MidoJerseyServletModule.class);

    private final ServletContext servletContext;
    private final static Map<String, String> servletParams = new
            HashMap<String, String>();
    static {
        servletParams.put(ResourceConfig.PROPERTY_CONTAINER_REQUEST_FILTERS,
                AuthContainerRequestFilter.class.getName());
        servletParams.put(ResourceConfig.PROPERTY_CONTAINER_RESPONSE_FILTERS,
                ExceptionFilter.class.getName());
        servletParams.put(ResourceConfig.PROPERTY_RESOURCE_FILTER_FACTORIES,
                RolesAllowedResourceFilterFactory.class.getName());
    }

    public MidoJerseyServletModule(ServletContext servletContext) {
        this.servletContext = servletContext;
    }

    @Override
    protected void configureServlets() {
        log.debug("MidoJerseyServletModule.configureServlets: entered");

        // Install configuration bindings
        install(new ConfigurationModule(servletContext));

        // Install REST API bindings
        install(new RestApiModule());

        // Install data store module
        install(new DataStoreModule());

        // Install DAO modules
        install(new DaoModule());

        // Install JAX-RS bindings
        install(new JaxrsModule());

        // Install Auth
        install(new AuthModule());

        // Install validations
        install(new ValidationModule());

        // Register filters
        filter("/v1/*").through(AuthFilter.class);
        filter("/v1/*").through(CrossOriginResourceSharingFilter.class);
        filter("/*").through(AuthFilter.class);
        filter("/*").through(CrossOriginResourceSharingFilter.class);

        // Register servlet
        serve("/v1/*").with(GuiceContainer.class, servletParams);
        serve("/*").with(GuiceContainer.class, servletParams);

        log.debug("MidoJerseyServletModule.configureServlets: exiting");
    }

}
