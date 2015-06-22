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
package org.midonet.api.rest_api;

import java.net.URI;
import java.util.UUID;

import javax.servlet.ServletContextEvent;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.servlet.GuiceFilter;
import com.google.inject.servlet.GuiceServletContextListener;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.WebAppDescriptor;
import com.typesafe.config.ConfigFactory;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.retry.RetryNTimes;
import org.apache.curator.test.TestingServer;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.LoggerFactory;

import org.midonet.api.auth.AuthConfig;
import org.midonet.api.servlet.JerseyGuiceTestServletContextListener;
import org.midonet.cluster.ClusterConfig;
import org.midonet.cluster.rest_api.jaxrs.WildCardJacksonJaxbJsonProvider;
import org.midonet.cluster.rest_api.serialization.ObjectMapperProvider;
import org.midonet.cluster.services.MidonetBackendService;
import org.midonet.cluster.services.rest_api.Vladimir;
import org.midonet.cluster.services.rest_api.serialization.MidonetObjectMapper;
import org.midonet.conf.HostIdGenerator;

import static org.apache.curator.framework.CuratorFrameworkFactory.newClient;

public class FuncTest {
    static final ClientConfig config = new DefaultClientConfig();

    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";
    public static final int ZK_TEST_PORT = (int)(Math.random() * 1000) + 63000;
    public static final String ZK_TEST_SERVER = "127.0.0.1:" + ZK_TEST_PORT;

    public final static String BASE_URI_CONFIG = "rest_api-base_uri";
    public final static String CONTEXT_PATH = "/test";
    public final static String OVERRIDE_BASE_URI =
            "http://127.0.0.1:9998" + CONTEXT_PATH;

    public static ObjectMapper objectMapper;

    // Can be used to access the Guice context that is created for the
    // embedded API.  The right place to set it is the
    // GuiceServletContextListener that is implemented to start up the API.
    public static Injector _injector = null;

    static {
        HostIdGenerator.useTemporaryHostId();
        objectMapper = new MidonetObjectMapper();
        objectMapper.configure(
            DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        // Randomize GrizzlyWebTestContainer's port for parallelism
        System.setProperty("jersey.test.port",
                String.valueOf((int)(Math.random() * 1000) + 62000));
    }

    public static WildCardJacksonJaxbJsonProvider jacksonJaxbJsonProvider;

    /**
     * Choose the right Builder.  Defaults to the old midonet-api, but will
     * return the new cluster-based midonet-api if the withVladimir env.
     * variable is set to "true".
     */
    public static WebAppDescriptor.Builder getBuilder() {
        if (isCompatApiEnabled()) {
            return getBuilderForVladimir();
        } else {
            return getBuilderForLegacyApi();
        }
    }

    /**
     * Tells if we're testing against the Compat API.
     */
    public static boolean isCompatApiEnabled() {
        return Boolean.parseBoolean(System.getenv("withVladimir"));
    }

    private static WebAppDescriptor.Builder getBuilderForLegacyApi() {

        ObjectMapperProvider mapperProvider = new ObjectMapperProvider();
        jacksonJaxbJsonProvider =
            new WildCardJacksonJaxbJsonProvider(mapperProvider);
        config.getSingletons().add(jacksonJaxbJsonProvider);

        String zkRoot = ZK_ROOT_MIDOLMAN + "_" + UUID.randomUUID();
        return new WebAppDescriptor.Builder()
                .contextListenerClass(JerseyGuiceTestServletContextListener.class)
                .filterClass(GuiceFilter.class)
                .servletPath("/")
                .contextParam(getConfigKey(AuthConfig.GROUP_NAME,
                                           AuthConfig.AUTH_PROVIDER),
                              "org.midonet.api.auth.MockAuthService")
                .contextParam(getConfigKey("zookeeper",
                                           "zookeeper_hosts"),
                                           FuncTest.ZK_TEST_SERVER)
                .contextParam(getConfigKey("zookeeper", "curator_enabled"), "true")
                .contextParam(getConfigKey("zookeeper", "midolman_root_key"),
                              zkRoot)
                .contextParam(getConfigKey("zookeeper", "root_key"), zkRoot)
                .contextParam(getConfigKey("zookeeper", "use_new_stack"),
                              "false")
                .contextPath(CONTEXT_PATH).clientConfig(config);
    }

    public static class VladimirServletContextListener
        extends GuiceServletContextListener {

        private TestingServer testZk;

        public VladimirServletContextListener () {
            try {
                testZk = new TestingServer(FuncTest.ZK_TEST_PORT);
            } catch (Exception e) {
                throw new IllegalStateException("Can't start Zookeeper server at " +
                                                FuncTest.ZK_TEST_PORT);
            }
        }

        @Override
        public void contextInitialized(ServletContextEvent sce) {
            super.contextInitialized(sce);
            try {
                testZk.start();
            } catch (Exception e) {
                throw new IllegalStateException("Can't start Zookeeper server at " +
                                                FuncTest.ZK_TEST_PORT);
            }
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce) {
            try {
                testZk.close();
            } catch (Exception e) {
                // OK
            }
            super.contextDestroyed(sce);
        }

        @Override
        protected Injector getInjector() {
            CuratorFramework curator = newClient(ZK_TEST_SERVER,
                                                 new RetryNTimes(10, 500));

            ClusterConfig cfg = new ClusterConfig(
                ConfigFactory.parseString (
                    "zookeeper.use_new_stack = true \n" +
                    "zookeeper.curator_enabled = true \n" +
                    "zookeeper.root_key = " + ZK_ROOT_MIDOLMAN + "\n" +
                    "cluster.rest_api.root_uri = " + CONTEXT_PATH
                )
            );

            MidonetBackendService backend =
                new MidonetBackendService(cfg.backend(), curator);
            backend.startAsync().awaitRunning();

            FuncTest._injector = Guice.createInjector(
                Vladimir.servletModule(backend, cfg),
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(TopologyBackdoor.class)
                        .to(ZoomTopologyBackdoor.class);
                    }
                }
            );

            return _injector;
        }
    }

    private static WebAppDescriptor.Builder getBuilderForVladimir() {
        config.getSingletons()
              .add(new Vladimir.WildcardJacksonJaxbJsonProvider());
        LoggerFactory.getLogger(FuncTest.class)
                     .info("TESTING MIDONET API AGAINST ZOOM IMPLEMENTATION");
        return new WebAppDescriptor.Builder()
            .contextListenerClass(VladimirServletContextListener.class)
            .filterClass(GuiceFilter.class)
            .servletPath("/")
            .contextPath(CONTEXT_PATH).clientConfig(config);
    }

    public static final AppDescriptor appDesc = getBuilder().build();

    public static final AppDescriptor appDescOverrideBaseUri =
        getBuilder().contextParam(BASE_URI_CONFIG, OVERRIDE_BASE_URI).build();

    public static UUID getUuidFromLocation(URI location) {
        if (location == null) {
            return null;
        }
        String[] tmp = location.toString().split("/");
        return UUID.fromString(tmp[tmp.length - 1]);
    }

    public static String getConfigKey(String section, String key) {
        return section + "-" + key;
    }
}
