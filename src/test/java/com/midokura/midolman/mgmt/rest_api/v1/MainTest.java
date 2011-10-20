package com.midokura.midolman.mgmt.rest_api.v1;

import org.codehaus.jackson.jaxrs.JacksonJaxbJsonProvider;
import org.junit.Test;

import com.midokura.midolman.mgmt.rest_api.v1.resources.TenantResource;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.WebAppDescriptor;
import com.sun.jersey.test.framework.spi.container.TestContainerFactory;
import com.sun.jersey.test.framework.spi.container.embedded.glassfish.EmbeddedGlassFishTestContainerFactory;
import com.sun.jersey.test.framework.spi.container.external.*;
import com.sun.jersey.test.framework.spi.container.grizzly.GrizzlyTestContainerFactory;
import com.sun.jersey.test.framework.spi.container.grizzly.web.GrizzlyWebTestContainerFactory;
import com.sun.jersey.test.framework.spi.container.http.HTTPContainerFactory;
import com.sun.jersey.test.framework.spi.container.inmemory.*;

public class MainTest extends JerseyTest {

    static ClientConfig cc;
    static {
        cc = new DefaultClientConfig();
        cc.getSingletons().add(new JacksonJaxbJsonProvider());
    }

    public MainTest() {
        super(new WebAppDescriptor.Builder(
                "com.midokura.midolman.mgmt.rest_api.v1.resources")
                .contextPath("context").clientConfig(cc).build());
    }

    @Test
    public void test() {
        WebResource resource;
        ClientResponse response;

        // Add the tenant
        resource = resource().path("/tenants");
        response = resource.post(ClientResponse.class);
        System.out.println(response.getLocation());

    }

    @Override
    protected TestContainerFactory getTestContainerFactory() {
        return new HTTPContainerFactory();
//        return new ExternalTestContainerFactory();
//        return new GrizzlyWebTestContainerFactory();

    }
}
