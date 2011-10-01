package com.midokura.midolman.mgmt.tools;

import javax.ws.rs.core.MediaType;

import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

public class CreateZkTestConfig {

    static String basePath = "http://mido-iida-2.midokura.jp:8081/midolmanj-mgmt/v1";

    public static void main(String[] args) {
        Client client = Client.create();
        WebResource resource;
        String url;
        ClientResponse response;

        /*
        url = new StringBuilder(basePath).append("/admin/init").toString();
        resource = client.resource(url);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class);
        */

        url = new StringBuilder(basePath).append("/tenants").toString();
        resource = client.resource(url);
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, new Tenant());
        System.out.println(response.getLocation());

        url = new StringBuilder(response.getLocation().toString()).append(
                "/routers").toString();
        resource = client.resource(url);
        Router rtr = new Router();
        rtr.setName("PinoDanTest");
        response = resource.type(MediaType.APPLICATION_JSON)
                .header("HTTP_X_AUTH_TOKEN", "999888777666")
                .post(ClientResponse.class, rtr);
        System.out.println(response.getLocation());
    }

}
