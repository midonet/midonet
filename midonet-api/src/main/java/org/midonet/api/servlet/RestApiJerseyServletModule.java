/*
 * Copyright 2015 Midokura SARL
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
import java.util.UUID;

import javax.servlet.ServletContext;

import com.sun.jersey.api.container.filter.LoggingFilter;
import com.sun.jersey.api.container.filter.RolesAllowedResourceFilterFactory;
import com.sun.jersey.api.core.ResourceConfig;
import com.sun.jersey.guice.JerseyServletModule;
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import org.apache.commons.lang3.StringUtils;
import org.midonet.brain.ClusterNode;
import org.midonet.cluster.config.ZookeeperConfig;
import org.midonet.conf.HostIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.auth.AuthContainerRequestFilter;
import org.midonet.api.auth.AuthFilter;
import org.midonet.api.auth.AuthModule;
import org.midonet.api.auth.LoginFilter;
import org.midonet.api.auth.StateFilter;
import org.midonet.api.auth.cors.CrossOriginResourceSharingFilter;
import org.midonet.api.error.ErrorModule;
import org.midonet.api.error.ExceptionFilter;
import org.midonet.api.network.NetworkModule;
import org.midonet.api.neutron.NeutronRestApiModule;
import org.midonet.api.rest_api.RestApiModule;
import org.midonet.api.serialization.SerializationModule;
import org.midonet.api.validation.ValidationModule;
import org.midonet.cluster.data.neutron.NeutronClusterApiModule;
import org.midonet.cluster.storage.MidonetBackendModule;
import org.midonet.config.ConfigProvider;
import org.midonet.config.providers.ServletContextConfigProvider;
import org.midonet.midolman.cluster.LegacyClusterModule;
import org.midonet.midolman.cluster.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.state.SessionUnawareConnectionWatcher;

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

    public static Config zkConfToConfig(ZookeeperConfig zkconf) {
        return ConfigFactory.empty()
            .withValue("zookeeper.zookeeper_hosts",
                ConfigValueFactory.fromAnyRef(zkconf.getZkHosts()))
            .withValue("zookeeper.session_gracetime",
                ConfigValueFactory.fromAnyRef(zkconf.getZkGraceTime()))
            .withValue("zookeeper.root_key",
                ConfigValueFactory.fromAnyRef(zkconf.getZkRootPath()))
            .withValue("zookeeper.midolman_root_key",
                ConfigValueFactory.fromAnyRef(zkconf.getZkRootPath()))
            .withValue("zookeeper.session_timeout",
                ConfigValueFactory.fromAnyRef(zkconf.getZkSessionTimeout()))
            .withValue("zookeeper.use_new_stack",
                ConfigValueFactory.fromAnyRef(false));
    }

    protected boolean clusterEmbedEnabled() {
        return true;
    }

    @Override
    protected void configureServlets() {
        HostIdGenerator.useTemporaryHostId();

        log.debug("configureServlets: entered");

        final ConfigProvider cfgProvider =
            new ServletContextConfigProvider(servletContext);

        ZookeeperConfig zkCfg = cfgProvider.getConfig(ZookeeperConfig.class);
        bind(ZookeeperConfig.class).toInstance(zkCfg);

        try {
            UUID clusterNodeId = HostIdGenerator.getHostId();
            bind(ClusterNode.Context.class).toInstance(
                    new ClusterNode.Context(clusterNodeId, clusterEmbedEnabled()));
        } catch (Exception e) {
            log.error("Could not register cluster node host id", e);
            throw new RuntimeException(e);
        }

        bind(ConfigProvider.class).toInstance(cfgProvider);
        install(new SerializationModule());
        install(new AuthModule());
        install(new ErrorModule());

        install(new MidonetBackendModule(zkConfToConfig(zkCfg)));

        installRestApiModule(); // allow mocking

        install(new ValidationModule());

        // Install Zookeeper module until Cluster Client makes it unnecessary
        install(new ZookeeperConnectionModule(
            SessionUnawareConnectionWatcher.class));
        install(new LegacyClusterModule());

        // Install Neutron module;
        install(new NeutronClusterApiModule());
        install(new NeutronRestApiModule());

        install(new NetworkModule());

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
