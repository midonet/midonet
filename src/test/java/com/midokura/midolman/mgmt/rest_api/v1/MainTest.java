package com.midokura.midolman.mgmt.rest_api.v1;

import com.midokura.midolman.mgmt.data.dto.Tenant;

import javax.ws.rs.core.MediaType;

import org.junit.Test;
import static org.junit.Assert.*;

import com.midokura.midolman.mgmt.rest_api.v1.resources.TenantResource;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.json.JSONConfiguration;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.WebAppDescriptor;
import com.sun.jersey.test.framework.spi.container.TestContainerFactory;
import com.sun.jersey.test.framework.spi.container.grizzly2.GrizzlyTestContainerFactory;
import com.sun.jersey.test.framework.spi.container.grizzly2.web.GrizzlyWebTestContainerFactory;

public class MainTest extends JerseyTest {

    static ClientConfig cc;
    static {
        cc = new DefaultClientConfig();
        cc.getFeatures().put(JSONConfiguration.FEATURE_POJO_MAPPING,
                             Boolean.TRUE);
    }

    public MainTest() {
        super(new WebAppDescriptor.Builder(
                  "com.midokura.midolman.mgmt.rest_api.v1.resources")
              .initParam(JSONConfiguration.FEATURE_POJO_MAPPING, "true")
              .contextPath("context").build());
    }

    @Test
    public void test() {
        WebResource resource;
        ClientResponse response;

        // Add the tenant
        resource = resource().path("tenants");
        response = resource.accept(
            MediaType.APPLICATION_JSON).post(ClientResponse.class,
                                             new Tenant());
        System.out.println(response.getLocation());
        // Loop if you want to test w/ curl.
        // try {
        //     Thread.sleep(100000);
        // } catch (Exception e) {
        // }
    }

    @Override
    protected TestContainerFactory getTestContainerFactory() {
        return new GrizzlyWebTestContainerFactory();
    }
}
