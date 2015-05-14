/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.api.auth.cors;

import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.api.rest_api.FuncTest;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestCorsHeaders extends JerseyTest {

    private final static Logger log =
        LoggerFactory.getLogger(TestCorsHeaders.class);

    public TestCorsHeaders() {
        super(FuncTest.appDesc);
    }

    @Test
    public void testCorsHeaders() {
        WebResource resource = resource().path("/");
        // Test OPTIONS method returns expected response headers.
        ClientResponse response = resource.options(ClientResponse.class);
        log.debug("status: {}", response.getStatus());
        assertEquals(200, response.getStatus());
        Map<String, List<String>> headers = response.getHeaders();
        List<String> origin = headers.get("Access-Control-Allow-Origin");
        log.debug("Access-Control-Allow-Origin: {}", origin);
        assertTrue(origin.contains("*"));
        String allowHeaders = headers.get("Access-Control-Allow-Headers")
                                     .get(0);
        log.debug("Access-Control-Allow-Headers: {}", allowHeaders);
        assertTrue(allowHeaders.contains("Origin"));
        assertTrue(allowHeaders.contains("X-Auth-Token"));
        assertTrue(allowHeaders.contains("Content-Type"));
        assertTrue(allowHeaders.contains("Accept"));
        String allowMethods = headers.get("Access-Control-Allow-Methods")
                                     .get(0);
        assertTrue(allowMethods.contains("GET"));
        assertTrue(allowMethods.contains("POST"));
        assertTrue(allowMethods.contains("PUT"));
        assertTrue(allowMethods.contains("DELETE"));
        assertTrue(allowMethods.contains("OPTIONS"));
        String exposeHeaders = headers.get("Access-Control-Expose-Headers")
                                      .get(0);
        assertTrue(exposeHeaders.contains("Location"));

        // Test GET method returns expected response headers.
        response = resource.accept(VendorMediaType.APPLICATION_JSON_V5)
                           .header("X-Auth-Token", "999888777666")
                           .get(ClientResponse.class);
        String body = response.getEntity(String.class);
        log.debug("status: {}", response.getStatus());
        log.debug("body: {}", body);
        assertEquals(200, response.getStatus());
        headers = response.getHeaders();
        origin = headers.get("Access-Control-Allow-Origin");
        log.debug("Access-Control-Allow-Origin: {}", origin);
        assertTrue(origin.contains("*"));
        allowMethods = headers.get("Access-Control-Allow-Methods").get(0);
        assertTrue(allowMethods.contains("GET"));
        assertTrue(allowMethods.contains("POST"));
        assertTrue(allowMethods.contains("PUT"));
        assertTrue(allowMethods.contains("DELETE"));
        assertTrue(allowMethods.contains("OPTIONS"));
        exposeHeaders = headers.get("Access-Control-Expose-Headers").get(0);
        assertTrue(exposeHeaders.contains("Location"));
    }
}
