package com.midokura.midolman.mgmt.rest_api.v1;

import org.codehaus.jackson.jaxrs.JacksonJaxbJsonProvider;
import org.junit.Test;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.WebAppDescriptor;

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

}
