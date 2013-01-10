/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import com.google.inject.servlet.GuiceFilter;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.WebAppDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.servlet.JerseyGuiceServletContextListener;
import com.midokura.midonet.client.jaxrs.WildCardJacksonJaxbJsonProvider;

public class ApiServer extends JerseyTest {
    private final static Logger log =
        LoggerFactory.getLogger(ApiServer.class);

    String uri;

    public static WebAppDescriptor.Builder getAppDescriptorBuilder(int zkPort, int cassandraPort) {
        ClientConfig clientConfig = new DefaultClientConfig();
        clientConfig.getSingletons().add(new WildCardJacksonJaxbJsonProvider());

        return new WebAppDescriptor.Builder()
            .contextListenerClass(JerseyGuiceServletContextListener.class)
            .filterClass(GuiceFilter.class)
            .servletPath("/")
            .contextParam("rest_api-version", "1")
            .contextParam("cors-access_control_allow_origin", "*")
            .contextParam("cors-access_control_allow_headers",
                "Origin, X-Auth-Token, Content-Type, Accept")
            .contextParam("cors-access_control_allow_methods",
                "GET, POST, PUT, DELETE, OPTIONS")
            .contextParam("cors-access_control-expose_headers",
                "Location")
            .contextParam("auth-auth_provider",
                    "com.midokura.midolman.mgmt.auth.MockAuthClient")
            .contextParam("zookeeper-midolman_root_key",
                "/smoketest/midonet")
            .contextParam("cassandra-servers", "127.0.0.1:" + cassandraPort)
            .contextParam("monitoring-cassandra_replication_factor", "1")
            .contextParam("zookeeper-zookeeper_hosts", "127.0.0.1:" + zkPort)
            .contextParam("zookeeper-session_timeout", "10000")
            .contextParam("zookeeper-use_mock", "false")
            .contextPath("/test")
            .clientConfig(clientConfig);
    }

    /**
     * Starts the api with the mock zookeeper and the default cassandra port.
     */
    public ApiServer() {
        this(getAppDescriptorBuilder(2181, 9171).build());
    }

    /**
     * Starts the api with the given zookeper and the default cassandra port.
     * @param zkPort
     */
    public ApiServer(int zkPort) {
        this(getAppDescriptorBuilder(zkPort, 9171).build());
    }

    public ApiServer(WebAppDescriptor webAppDescriptor) {
        super(webAppDescriptor);
        uri = resource().getURI().toString();
    }

    public String getURI() {
        return uri;
    }

    public void stop() {
        log.info("Shutting down the WebApplication !");
        try {
            tearDown();
        } catch (Exception e) {
            log.error("While shutting down the mock manager:", e);
        }
    }
}
