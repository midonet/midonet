/*
 * Copyright 2016 Midokura SARL
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
package org.midonet.cluster.rest_api.rest_api;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.Executors;

import javax.servlet.ServletContextEvent;

import scala.concurrent.ExecutionContext;
import scala.concurrent.ExecutionContext$;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.typesafe.scalalogging.Logger;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.retry.RetryNTimes;
import org.apache.curator.test.TestingServer;
import org.reflections.Reflections;

import org.midonet.cluster.ClusterConfig;
import org.midonet.cluster.auth.AuthService;
import org.midonet.cluster.auth.MockAuthService;
import org.midonet.cluster.data.storage.StateTableStorage;
import org.midonet.cluster.data.storage.model.ArpEntry;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.jaxrs.WildcardJacksonJaxbJsonProvider;
import org.midonet.cluster.rest_api.serialization.MidonetObjectMapper;
import org.midonet.cluster.rest_api.serialization.ObjectMapperProvider;
import org.midonet.cluster.services.MidonetBackend;
import org.midonet.cluster.services.MidonetBackendService;
import org.midonet.cluster.services.rest_api.RestApi;
import org.midonet.cluster.storage.ArpStateTable;
import org.midonet.cluster.storage.Ip4MacStateTable;
import org.midonet.cluster.storage.MacIdStateTable;
import org.midonet.conf.HostIdGenerator;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.southbound.vtep.MockOvsdbVtepConnectionProvider;
import org.midonet.southbound.vtep.OvsdbVtepConnectionProvider;
import org.midonet.util.concurrent.NamedThreadFactory;

import static com.fasterxml.jackson.databind.DeserializationFeature.*;
import static org.apache.curator.framework.CuratorFrameworkFactory.newClient;
import static org.slf4j.LoggerFactory.getLogger;

public class FuncTest {
    static final ClientConfig config = new DefaultClientConfig();

    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";

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
        objectMapper.configure(FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public static WebAppDescriptor.Builder getBuilder() {
        config.getSingletons()
              .add(new WildcardJacksonJaxbJsonProvider(
                  new ObjectMapperProvider()));
        return new WebAppDescriptor.Builder()
            .contextListenerClass(RestApiServletContextListener.class)
            .filterClass(GuiceFilter.class)
            .servletPath("/")
            .contextPath(CONTEXT_PATH).clientConfig(config);
    }

    public static class RestApiServletContextListener
        extends GuiceServletContextListener {

        private final TestingServer testZk;
        private final CuratorFramework curator;
        private final MidonetBackendService backend;
        private final ClusterConfig cfg;

        public RestApiServletContextListener () {
            try {
                // This starts a Zookeeper server on an available port that
                // is randomly selected.
                testZk = new TestingServer();
                curator = newClient(testZk.getConnectString(),
                                    new RetryNTimes(10, 500));
            } catch (Exception e) {
                throw new IllegalStateException("Can't start Zookeeper server");
            }

            cfg = ClusterConfig.forTests(
                ConfigFactory.parseString (
                    "zookeeper.curator_enabled = true \n" +
                    "zookeeper.root_key = " + ZK_ROOT_MIDOLMAN + "\n" +
                    "cluster.rest_api.root_uri = " + CONTEXT_PATH + "\n" +
                    "cluster.auth.provider_class = \"org.midonet.cluster.auth.MockAuthService\""
                )
            );

            backend = new MidonetBackendService(cfg.backend(), curator, curator,
                                                new MetricRegistry(),
                                                scala.Option.apply(null)) {
                    @Override
                    public void setup(StateTableStorage storage) {
                        storage.registerTable(
                            Topology.Network.class, MAC.class, UUID.class,
                            MidonetBackend.MacTable(), MacIdStateTable.class);
                        storage.registerTable(
                            Topology.Network.class, IPv4Addr.class, MAC.class,
                            MidonetBackend.Ip4MacTable(),
                            Ip4MacStateTable.class);
                        storage.registerTable(
                            Topology.Router.class, IPv4Addr.class,
                            ArpEntry.class, MidonetBackend.ArpTable(),
                            ArpStateTable.class);
                    }
                };
        }

        @Override
        public void contextInitialized(ServletContextEvent sce) {
            super.contextInitialized(sce);
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce) {
            try {
                backend.stopAsync().awaitTerminated();
                testZk.close();
            } catch (Exception e) {
                // OK
            }
            super.contextDestroyed(sce);
        }

        @Override
        protected Injector getInjector() {

            Reflections reflections = new Reflections(
                "org.midonet.cluster.rest_api",
                "org.midonet.cluster.services.rest_api");

            AuthService authService = new MockAuthService(cfg.conf());

            backend.startAsync().awaitRunning();

            ExecutionContext ec = ExecutionContext$.MODULE$.fromExecutor(
                Executors.newCachedThreadPool(
                    new NamedThreadFactory("rest-api", true)));

            FuncTest._injector = Guice.createInjector(
                RestApi.servletModule(
                    backend, ec, curator, cfg, reflections, authService,
                    Logger.apply(getLogger(getClass()))),
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(OvsdbVtepConnectionProvider.class)
                            .to(MockOvsdbVtepConnectionProvider.class);
                        bind(TopologyBackdoor.class)
                            .to(ZoomTopologyBackdoor.class);
                    }
                }
            );

            return _injector;
        }
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
}
