package com.midokura.midolman.mgmt.tools;

import javax.ws.rs.core.MediaType;

import org.codehaus.jackson.jaxrs.JacksonJaxbJsonProvider;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;

public class DeleteZkTestConfig {

    static String basePath = "http://mido-iida-2.midokura.jp:8081/midolmanj-mgmt/v1";

    public static void main(String[] args) {
        WebResource resource;
        String url;

        ClientConfig cc = new DefaultClientConfig();
        cc.getSingletons().add(new JacksonJaxbJsonProvider());
        Client client = Client.create(cc);

        String[] tenants = new String[] { 
                "525106eb-ede2-47d0-8db2-dca0f4e49cce"
        };
        for (String tenant : tenants) {
            url = new StringBuilder(basePath).append("/tenants/")
                    .append(tenant).toString();
            resource = client.resource(url);
            String answer = resource.type(MediaType.APPLICATION_JSON)
                    .header("HTTP_X_AUTH_TOKEN", "999888777666")
                    .delete(String.class);
            System.out.println(answer);
        }
    }
}
