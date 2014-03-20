/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.servlet;

import com.google.inject.Key;
import com.sun.jersey.api.container.filter.RolesAllowedResourceFilterFactory;
import com.sun.jersey.api.core.ResourceConfig;
import com.sun.jersey.guice.JerseyServletModule;
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;
import org.midonet.api.auth.AuthContainerRequestFilter;
import org.midonet.api.auth.AuthFilter;
import org.midonet.api.auth.StateFilter;
import org.midonet.api.auth.AuthModule;
import org.midonet.api.auth.LoginFilter;
import org.midonet.api.auth.cors.CrossOriginResourceSharingFilter;
import org.midonet.api.config.ConfigurationModule;
import org.midonet.api.error.ErrorModule;
import org.midonet.api.error.ExceptionFilter;
import org.midonet.api.filter.FilterModule;
import org.midonet.api.network.NetworkModule;
import org.midonet.api.rest_api.RestApiModule;
import org.midonet.api.serialization.SerializationModule;
import org.midonet.api.validation.ValidationModule;
import org.midonet.api.zookeeper.ZookeeperModule;
import org.midonet.midolman.guice.CacheModule;
import org.midonet.midolman.guice.MonitoringStoreModule;
import org.midonet.midolman.guice.cluster.DataClusterClientModule;
import org.midonet.midolman.version.guice.VersionModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import java.util.HashMap;
import java.util.Map;

/**
 * Jersey servlet module for MidoNet REST API application.
 */
public class RestApiJerseyServletModule extends JerseyServletModule {

    private final static Logger log = LoggerFactory
            .getLogger(RestApiJerseyServletModule.class);

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

    public RestApiJerseyServletModule(ServletContext servletContext) {
        this.servletContext = servletContext;
    }

    @Override
    protected void configureServlets() {
        log.debug("configureServlets: entered");

        install(new ConfigurationModule(servletContext));
        install(new VersionModule());
        install(new AuthModule());
        install(new ErrorModule());
        install(new RestApiModule());
        install(new SerializationModule());
        install(new ValidationModule());

        // Install Zookeeper module until Cluster Client makes it unnecessary
        install(new ZookeeperModule());
        install(new CacheModule());
        install(new DataClusterClientModule());
        install(new MonitoringStoreModule());

        install(new NetworkModule());
        install(new FilterModule());

        // Register filters - the order matters here.  Make sure that CORS
        // filter is registered first because Auth would reject OPTION
        // requests without a token in the header. The Login filter relies
        // on CORS as well.
        filter("/*").through(CrossOriginResourceSharingFilter.class);
        filter("/login").through(LoginFilter.class);
        filter("/*").through(AuthFilter.class);
        filter("/*").through(StateFilter.class);

        // Register servlet
        serve("/*").with(GuiceContainer.class, servletParams);

        log.debug("configureServlets: exiting");
    }

}
