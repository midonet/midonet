/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.midokura.midolman.mgmt.auth.NoAuthClient;
import com.midokura.midolman.mgmt.jaxrs.WildCardJacksonJaxbJsonProvider;
import com.midokura.midolman.mgmt.servlet.AuthFilter;
import com.midokura.midolman.mgmt.servlet.CrossOriginResourceSharingFilter;
import com.midokura.midolman.mgmt.servlet.ServletSupport;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.json.JSONConfiguration;
import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.WebAppDescriptor;

public class FuncTest {
    static final ClientConfig config = new DefaultClientConfig();
    static final Map<String, String> corsFilterInitParams =
        new HashMap<String, String>();
    static {
        config.getSingletons().add(new WildCardJacksonJaxbJsonProvider());
        corsFilterInitParams.put("Access-Control-Allow-Origin", "*");
        corsFilterInitParams.put("Access-Control-Allow-Headers",
                                 "Origin, HTTP_X_AUTH_TOKEN, Content-Type, " +
                                 "Accept");
        corsFilterInitParams.put("Access-Control-Allow-Methods",
                                 "GET, POST, PUT, DELETE, OPTIONS");
        corsFilterInitParams.put("Access-Control-Expose-Headers",
                                 "Location");
    }

    static final Map<String, String> authFilterInitParams = new HashMap<String, String>();
    static {
        authFilterInitParams.put(ServletSupport.AUTH_CLIENT_CONFIG_KEY,
                NoAuthClient.class.getName());
    }

    static final WebAppDescriptor.Builder getBuilder() {
        return new WebAppDescriptor.Builder()
            .addFilter(AuthFilter.class, "auth", authFilterInitParams)
            .addFilter(CrossOriginResourceSharingFilter.class, "cors",
                       corsFilterInitParams)
            .initParam(JSONConfiguration.FEATURE_POJO_MAPPING, "true")
            .initParam("com.sun.jersey.spi.container.ContainerRequestFilters",
                    "com.midokura.midolman.mgmt.auth.AuthContainerRequestFilter")
            .initParam("com.sun.jersey.spi.container.ContainerResponseFilters",
                    "com.midokura.midolman.mgmt.rest_api.resources.ExceptionFilter")
            .initParam("javax.ws.rs.Application",
                    "com.midokura.midolman.mgmt.rest_api.RestApplication")
            .initParam("com.sun.jersey.spi.container.ResourceFilters",
                    "com.sun.jersey.api.container.filter.RolesAllowedResourceFilterFactory")
            .contextParam("datastore_service",
                    "com.midokura.midolman.mgmt.data.MockDaoFactory")
            .contextParam("authorizer",
                    "com.midokura.midolman.mgmt.auth.SimpleAuthorizer")
            .contextParam("version", "1.0")
            .contextParam("zk_conn_string", "").contextParam("zk_timeout", "0")
            .contextParam("zk_root", "/test/midolman")
            .contextParam("zk_mgmt_root", "/test/midolman-mgmt")
            .contextPath("/test").clientConfig(config);
    }

    static final AppDescriptor appDesc = getBuilder().build();

    public static UUID getUuidFromLocation(URI location) {
        String[] tmp = location.toString().split("/");
        return UUID.fromString(tmp[tmp.length - 1]);
    }
}
