/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
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
import org.midonet.api.version.VersionParser;
import org.midonet.brain.configuration.MidoBrainConfig;
import org.midonet.cluster.config.ZookeeperConfig;

public class FuncTest {
    static final ClientConfig config = new DefaultClientConfig();

    private static final String ZK_ROOT_MIDOLMAN = "/test/midolman";

    public final static String BASE_URI_CONFIG = "rest_api-base_uri";
    public final static String CONTEXT_PATH = "/test";
    public final static String OVERRIDE_BASE_URI =
            "http://127.0.0.1:9998" + CONTEXT_PATH;

    public static ObjectMapper objectMapper;

    static {
        objectMapper = new ObjectMapper();
        objectMapper.configure(
                DeserializationConfig.Feature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public static WildCardJacksonJaxbJsonProvider jacksonJaxbJsonProvider;

    public static WebAppDescriptor.Builder getBuilder() {

        VersionParser parser = new VersionParser();
        ObjectMapperProvider mapperProvider = new ObjectMapperProvider();
        jacksonJaxbJsonProvider = new WildCardJacksonJaxbJsonProvider(
                mapperProvider, parser);
        config.getSingletons().add(jacksonJaxbJsonProvider);


        UUID testRunUuid = UUID.randomUUID();

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
                              "org.midonet.api.auth.MockAuthService")
                .contextParam(getConfigKey(ZookeeperConfig.DEFAULT_HOSTS,
                                           "zookeeper_hosts"),
                                           "127.0.0.1:2181")
                .contextParam(getConfigKey(ZookeeperConfig.GROUP_NAME,
                                           "curator_enabled"), "true")
                .contextParam(getConfigKey(ZookeeperConfig.GROUP_NAME,
                                           "midolman_root_key"),
                              ZK_ROOT_MIDOLMAN + "_" + UUID.randomUUID())
                .contextParam(getConfigKey(MidoBrainConfig.GROUP_NAME,
                                           "properties_file"),
                              "/tmp/" + testRunUuid + "_host_uuid.properties")
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
