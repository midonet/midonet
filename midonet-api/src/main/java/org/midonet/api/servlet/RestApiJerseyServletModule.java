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
package org.midonet.api.servlet;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletContext;

import com.sun.jersey.api.container.filter.LoggingFilter;
import com.sun.jersey.api.container.filter.RolesAllowedResourceFilterFactory;
import com.sun.jersey.api.core.ResourceConfig;
import com.sun.jersey.guice.JerseyServletModule;
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.auth.AuthContainerRequestFilter;
import org.midonet.api.auth.AuthFilter;
import org.midonet.api.auth.AuthModule;
import org.midonet.api.auth.LoginFilter;
import org.midonet.api.auth.StateFilter;
import org.midonet.api.auth.cors.CrossOriginResourceSharingFilter;
import org.midonet.api.config.ConfigurationModule;
import org.midonet.api.error.ErrorModule;
import org.midonet.api.error.ExceptionFilter;
import org.midonet.api.network.NetworkModule;
import org.midonet.api.neutron.NeutronRestApiModule;
import org.midonet.api.rest_api.RestApiModule;
import org.midonet.api.serialization.SerializationModule;
import org.midonet.api.validation.ValidationModule;
import org.midonet.api.zookeeper.ZookeeperModule;
import org.midonet.brain.MidoBrainModule;
import org.midonet.brain.guice.BrainModule;
import org.midonet.cluster.data.neutron.NeutronClusterModule;
import org.midonet.midolman.guice.cluster.DataClientModule;
import org.midonet.midolman.version.guice.VersionModule;

/**
 * Jersey servlet module for MidoNet REST API application.
 */
public class RestApiJerseyServletModule extends JerseyServletModule {

    private final static Logger log = LoggerFactory
            .getLogger(RestApiJerseyServletModule.class);

    protected final ServletContext servletContext;
    protected final static Map<String, String> servletParams = new HashMap<>();
    static {

        String[] requestFilters = new String[] {
            LoggingFilter.class.getName(),
            AuthContainerRequestFilter.class.getName()
        };

        String[] responseFilters = new String[] {
            ExceptionFilter.class.getName(),
            LoggingFilter.class.getName()
        };

        servletParams.put(ResourceConfig.PROPERTY_CONTAINER_REQUEST_FILTERS,
                          StringUtils.join(requestFilters, ";"));
        servletParams.put(ResourceConfig.PROPERTY_CONTAINER_RESPONSE_FILTERS,
                          StringUtils.join(responseFilters, ";"));
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
        install(new BrainModule());
        installRestApiModule(); // allow mocking
        install(new SerializationModule());
        install(new ValidationModule());

        // Install Zookeeper module until Cluster Client makes it unnecessary
        install(new ZookeeperModule());
        install(new DataClientModule());

        // Install Neutron module;
        install(new NeutronClusterModule());
        install(new NeutronRestApiModule());

        install(new NetworkModule());
        install(new MidoBrainModule());

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

    protected void installRestApiModule() {
        install(new RestApiModule());
    }

}
