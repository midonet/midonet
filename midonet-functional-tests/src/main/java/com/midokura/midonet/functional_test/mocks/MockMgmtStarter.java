package com.midokura.midonet.functional_test.mocks;

import com.google.inject.servlet.GuiceFilter;
import com.midokura.midolman.mgmt.host.HostInterfacePortMap;
import com.midokura.midolman.mgmt.servlet.JerseyGuiceServletContextListener;
import com.midokura.midonet.client.VendorMediaType;
import com.midokura.midonet.client.dto.*;
import com.midokura.midonet.client.jaxrs.WildCardJacksonJaxbJsonProvider;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.WebAppDescriptor;
import com.sun.jersey.test.framework.spi.container.TestContainerFactory;
import com.sun.jersey.test.framework.spi.container.grizzly2.web.GrizzlyWebTestContainerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

public class MockMgmtStarter extends JerseyTest {

    private final static Logger log =
            LoggerFactory.getLogger(MockMgmtStarter.class);


    String uri;

    public static WebAppDescriptor.Builder getAppDescriptorBuilder(boolean mockZK) {
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
                .contextParam("auth-use_mock", "true")
                .contextParam("zookeeper-midolman_root_key",
                        "/smoketest/midonet")
                .contextParam("cassandra-servers", "127.0.0.1:9171")
                .contextParam("zookeeper-zookeeper_hosts", "127.0.0.1:2181")
                .contextParam("zookeeper-session_timeout", "10000")
                .contextParam("zookeeper-use_mock", mockZK ? "true" : "false")
                .contextPath("/test")
                .clientConfig(clientConfig);
    }

    public MockMgmtStarter(boolean mockZK) {
        this(getAppDescriptorBuilder(mockZK).build());
    }

    public MockMgmtStarter(WebAppDescriptor webAppDescriptor) {
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
