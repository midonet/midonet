/*
 * @(#)FuncTest        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;

import java.net.URI;
import java.util.UUID;

import com.midokura.midolman.mgmt.rest_api.jaxrs.WildCardJacksonJaxbJsonProvider;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.json.JSONConfiguration;
import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.WebAppDescriptor;

public class FuncTest {
    static final AppDescriptor appDesc = new WebAppDescriptor.Builder(
            "com.midokura.midolman.mgmt.rest_api.resources",
            "com.midokura.midolman.mgmt.rest_api.jaxrs",
            "com.midokura.midolman.mgmt.data")
            .initParam(JSONConfiguration.FEATURE_POJO_MAPPING, "true")
            .initParam(
                    "com.sun.jersey.spi.container.ContainerRequestFilters",
                    "com.midokura.midolman.mgmt.auth.NoAuthFilter")
            .contextParam("datastore_service",
                    "com.midokura.midolman.mgmt.data.MockDaoFactory")
            .contextParam("zk_conn_string", "")
            .contextParam("zk_timeout", "0")
            .contextParam("zk_root", "/test/midolman")
            .contextParam("zk_mgmt_root", "/test/midolman-mgmt")
            .contextPath("/test")
            .clientConfig(new DefaultClientConfig(WildCardJacksonJaxbJsonProvider.class))
            .build();

    public static UUID getUuidFromLocation(URI location) {
        String[] tmp = location.toString().split("/");
        return UUID.fromString(tmp[tmp.length - 1]);
    }
}
