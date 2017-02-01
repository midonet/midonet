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
package org.midonet.api.rest_api;

import java.net.URI;
import java.util.UUID;

import com.google.inject.servlet.GuiceFilter;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.WebAppDescriptor;

import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;

import org.midonet.api.auth.AuthConfig;
import org.midonet.api.auth.cors.CorsConfig;
import org.midonet.api.serialization.ObjectMapperProvider;
import org.midonet.api.serialization.WildCardJacksonJaxbJsonProvider;
import org.midonet.api.servlet.JerseyGuiceTestServletContextListener;
import org.midonet.conf.HostIdGenerator;

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

    static {
        HostIdGenerator.useTemporaryHostId();
        objectMapper = new ObjectMapper();
        objectMapper.configure(
                DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        // Randomize GrizzlyWebTestContainer's port for parallelism
        System.setProperty("jersey.test.port",
                String.valueOf((int)(Math.random() * 1000) + 62000));
    }

    public static WildCardJacksonJaxbJsonProvider jacksonJaxbJsonProvider;

    public static WebAppDescriptor.Builder getBuilder() {

        ObjectMapperProvider mapperProvider = new ObjectMapperProvider();
        jacksonJaxbJsonProvider =
            new WildCardJacksonJaxbJsonProvider(mapperProvider);
        config.getSingletons().add(jacksonJaxbJsonProvider);


        UUID testRunUuid = UUID.randomUUID();

        String zkRoot = ZK_ROOT_MIDOLMAN + "_" + UUID.randomUUID();
        return new WebAppDescriptor.Builder()
                .contextListenerClass(JerseyGuiceTestServletContextListener.class)
                .filterClass(GuiceFilter.class)
                .servletPath("/")
                .contextParam(getConfigKey(CorsConfig.GROUP_NAME,
                                           CorsConfig.ALLOW_ORIGIN_KEY), "*")
                .contextParam(getConfigKey(CorsConfig.GROUP_NAME,
                                           CorsConfig.ALLOW_HEADERS_KEY),
                              "Origin, X-Auth-Token, Content-Type, Accept")
                .contextParam(getConfigKey(CorsConfig.GROUP_NAME,
                                           CorsConfig.ALLOW_METHODS_KEY),
                              "GET, POST, PUT, DELETE, OPTIONS")
                .contextParam(getConfigKey(CorsConfig.GROUP_NAME,
                                           CorsConfig.EXPOSE_HEADERS_KEY),
                              "Location")
                .contextParam(getConfigKey(AuthConfig.GROUP_NAME,
                                           AuthConfig.AUTH_PROVIDER),
                              "org.midonet.cluster.auth.MockAuthService")
                .contextParam(getConfigKey("zookeeper",
                                           "zookeeper_hosts"),
                              FuncTest.ZK_TEST_SERVER)
                .contextParam(getConfigKey("zookeeper", "curator_enabled"),
                              "true")
                .contextParam(getConfigKey("zookeeper", "midolman_root_key"), zkRoot)
                .contextParam(getConfigKey("zookeeper", "root_key"), zkRoot)
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
