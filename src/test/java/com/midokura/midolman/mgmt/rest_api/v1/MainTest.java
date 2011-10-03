package com.midokura.midolman.mgmt.rest_api.v1;

import org.junit.Test;

import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.WebAppDescriptor;
import com.sun.jersey.test.framework.spi.container.inmemory.InMemoryTestContainerFactory;

public class MainTest extends JerseyTest {

    protected AppDescriptor configure() {
        this.setTestContainerFactory(new InMemoryTestContainerFactory());
        return new WebAppDescriptor.Builder("mido-iida-2.midokura.jp")
                .contextPath("/").build();
    }

    @Test
    public void test() {
        // WebResource resource;
        // String url;
        // ClientResponse response;
        //
        // ClientConfig cc = new DefaultClientConfig();
        // cc.getSingletons().add(new JacksonJaxbJsonProvider());
        // Client client = Client.create(cc);
        // //client.addFilter(new LoggingFilter());
        //
        // url = new StringBuilder(basePath).append("/admin/init").toString();
        // resource = client.resource(url);
        // response = resource.type(MediaType.APPLICATION_JSON)
        // .header("HTTP_X_AUTH_TOKEN", "999888777666")
        // .post(ClientResponse.class);

        System.out.println("hi");
    }

}
