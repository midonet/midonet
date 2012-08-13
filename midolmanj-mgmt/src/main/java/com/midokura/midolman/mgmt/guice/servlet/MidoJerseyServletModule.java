/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.servlet;

import com.midokura.midolman.mgmt.guice.config.ConfigModule;
import com.midokura.midolman.mgmt.guice.jaxrs.JaxrsModule;
import com.midokura.midolman.mgmt.guice.rest.RestApiModule;
import com.sun.jersey.guice.JerseyServletModule;
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;

import javax.servlet.ServletContext;

/**
 * Jersey servlet module for Midolman API application.
 */
public class MidoJerseyServletModule extends JerseyServletModule {

    private final ServletContext servletContext;

    public MidoJerseyServletModule(ServletContext servletContext) {
        this.servletContext = servletContext;
    }
    @Override
    protected void configureServlets() {

        // Install configuration bindings
        install(new ConfigModule(servletContext));

        // Install REST API bindings
        install(new RestApiModule());

        // Install JAX-RS bindings
        install(new JaxrsModule());

        serve("/*").with(GuiceContainer.class);

    }

}
