/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.rest_api;

import com.google.inject.servlet.GuiceFilter;
import org.codehaus.jackson.map.DeserializationConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.midonet.api.auth.AuthConfig;
import org.midonet.api.auth.cors.CorsConfig;
import org.midonet.api.serialization.ObjectMapperProvider;
import org.midonet.api.serialization.WildCardJacksonJaxbJsonProvider;
import org.midonet.api.servlet.JerseyGuiceServletContextListener;
import org.midonet.api.version.VersionParser;
import org.midonet.api.zookeeper.ExtendedZookeeperConfig;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.WebAppDescriptor;

import java.net.URI;
import java.util.UUID;

public class FuncTest {
    static final ClientConfig config = new DefaultClientConfig();

    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";

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

    public static final WebAppDescriptor.Builder getBuilder() {

        VersionParser parser = new VersionParser();
        ObjectMapperProvider mapperProvider = new ObjectMapperProvider();
        jacksonJaxbJsonProvider = new WildCardJacksonJaxbJsonProvider(
                mapperProvider, parser);
        config.getSingletons().add(jacksonJaxbJsonProvider);

        return new WebAppDescriptor.Builder()
                .contextListenerClass(JerseyGuiceServletContextListener.class)
                .filterClass(GuiceFilter.class)
                .servletPath("/")
                .contextParam(
                        getConfigKey(CorsConfig.GROUP_NAME,
                                CorsConfig.ALLOW_ORIGIN_KEY), "*")
                .contextParam(
                        getConfigKey(CorsConfig.GROUP_NAME,
                                CorsConfig.ALLOW_HEADERS_KEY),
                        "Origin, X-Auth-Token, Content-Type, Accept")
                .contextParam(
                        getConfigKey(CorsConfig.GROUP_NAME,
                                CorsConfig.ALLOW_METHODS_KEY),
                        "GET, POST, PUT, DELETE, OPTIONS")
                .contextParam(
                        getConfigKey(CorsConfig.GROUP_NAME,
                                CorsConfig.EXPOSE_HEADERS_KEY), "Location")
                .contextParam(
                        getConfigKey(AuthConfig.GROUP_NAME,
                                AuthConfig.AUTH_PROVIDER),
                        "org.midonet.api.auth.MockAuthService")
                .contextParam(
                        getConfigKey(ExtendedZookeeperConfig.GROUP_NAME,
                                ExtendedZookeeperConfig.USE_MOCK_KEY), "true")
                .contextParam(
                        getConfigKey(ExtendedZookeeperConfig.GROUP_NAME,
                                "midolman_root_key"), ZK_ROOT_MIDOLMAN)
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
