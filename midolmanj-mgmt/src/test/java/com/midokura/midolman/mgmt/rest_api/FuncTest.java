/*
 * @(#)FuncTest        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.midokura.midolman.mgmt.rest_api.jaxrs.WildCardJacksonJaxbJsonProvider;
import com.midokura.midolman.mgmt.servlet.CrossOriginResourceSharingFilter;
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

    static final AppDescriptor appDesc = new WebAppDescriptor.Builder()
            .addFilter(CrossOriginResourceSharingFilter.class, "cors",
                       corsFilterInitParams)
            .initParam(JSONConfiguration.FEATURE_POJO_MAPPING, "true")
            .initParam("com.sun.jersey.spi.container.ContainerRequestFilters",
                    "com.midokura.midolman.mgmt.auth.NoAuthFilter")
            .initParam("com.sun.jersey.spi.container.ContainerResponseFilters",
                    "com.midokura.midolman.mgmt.rest_api.resources.ExceptionFilter")
            .initParam("javax.ws.rs.Application",
                    "com.midokura.midolman.mgmt.rest_api.RestApplication")
            .contextParam("datastore_service",
                    "com.midokura.midolman.mgmt.data.MockDaoFactory")
            .contextParam("authorizer",
                    "com.midokura.midolman.mgmt.auth.SimpleAuthorizer")
            .contextParam("zk_conn_string", "").contextParam("zk_timeout", "0")
            .contextParam("zk_root", "/test/midolman")
            .contextParam("zk_mgmt_root", "/test/midolman-mgmt")
            .contextPath("/test").clientConfig(config).build();

    public static UUID getUuidFromLocation(URI location) {
        String[] tmp = location.toString().split("/");
        return UUID.fromString(tmp[tmp.length - 1]);
    }
}
