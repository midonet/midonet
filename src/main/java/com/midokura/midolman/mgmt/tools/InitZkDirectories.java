package com.midokura.midolman.mgmt.tools;

import javax.ws.rs.core.MediaType;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

public class InitZkDirectories {

    private final static String tokenHeader = "HTTP_X_AUTH_TOKEN";

    /**
     * @param args
     */
    public static void main(String[] args) {
        // TODO: Do better arg checking
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: InitZkDirectorires <API url>");
        }
        String url = args[0];
        String token = args[1];

        Client client = Client.create();
        WebResource webResource = client.resource(url + "/admin/init");
        ClientResponse response = webResource.type(MediaType.APPLICATION_JSON)
                .header(tokenHeader, token).post(ClientResponse.class, null);
        if (response.getStatus() != 200) {
            System.out.println("Error occurred:"
                    + response.getEntity(String.class));
        } else {
            System.out.println("Succeeded");
        }
        return;
    }

}
