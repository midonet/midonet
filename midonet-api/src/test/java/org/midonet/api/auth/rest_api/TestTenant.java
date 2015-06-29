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
package org.midonet.api.auth.rest_api;

import com.fasterxml.jackson.databind.JavaType;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

import org.junit.Before;
import org.junit.Test;

import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.Topology;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoTenant;
import org.midonet.cluster.rest_api.VendorMediaType;

import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

/**
 * Tests tenant API operations.  Currently assumes MockAuth is being used.
 */
public class TestTenant extends JerseyTest {

    private Topology topology;
    private DtoWebResource dtoResource;

    public TestTenant() {
        super(FuncTest.appDesc);
    }

    private void createActualTenants(int count) {
        Topology.Builder builder = new Topology.Builder(dtoResource);

        for (int i = 0 ; i < count ; i++) {
            DtoBridge bridge = new DtoBridge();
            String tenantId = Integer.toString(i);
            bridge.setName(tenantId);
            bridge.setTenantId(tenantId);
            builder.create(tenantId, bridge);
        }

        topology = builder.build();
    }

    private static DtoTenant getExpectedTenant(URI baseUri, URI tenantsUri,
                                               String id) {
        String uri = tenantsUri.toString() + "/" + id;
        DtoTenant t = new DtoTenant(id, id);
        t.setUri(UriBuilder.fromUri(uri).build());
        t.setRouters(UriBuilder.fromUri(baseUri + "routers?tenant_id=" + id)
                .build());
        t.setBridges(UriBuilder.fromUri(baseUri + "bridges?tenant_id=" + id)
                .build());
        t.setChains(UriBuilder.fromUri(baseUri + "chains?tenant_id=" + id)
                .build());
        t.setPortGroups(UriBuilder.fromUri(baseUri + "port_groups?tenant_id="
                + id).build());
        return t;
    }

    private static List<DtoTenant> getExpectedTenants(URI baseUri,
                                                      URI tenantsUri,
                                                      int startTenantId,
                                                      int endTenantId) {
        List<DtoTenant> tenants = new ArrayList<>();

        for (int i = startTenantId; i <= endTenantId; i++) {
            DtoTenant t = getExpectedTenant(baseUri, tenantsUri,
                    Integer.toString(i));
            tenants.add(t);
        }

        return tenants;
    }

    @Before
    public void setUp() {
        WebResource resource = resource();
        dtoResource = new DtoWebResource(resource);

        createActualTenants(10);
    }

    @Test
    public void testListTenants() throws Exception {

        // Get the expected list of DtoTenant objects
        DtoApplication app = topology.getApplication();
        List<DtoTenant> expected = getExpectedTenants(app.getUri(),
                app.getTenants(), 0, 9);

        // Get the actual DtoTenant objects
        String actualRaw = dtoResource.getAndVerifyOk(app.getTenants(),
                VendorMediaType.APPLICATION_TENANT_COLLECTION_JSON,
                String.class);
        JavaType type = FuncTest.objectMapper.getTypeFactory()
                .constructParametrizedType(List.class, List.class,
                                           DtoTenant.class);
        List<DtoTenant> actual = FuncTest.objectMapper.readValue(
                actualRaw, type);

        // Compare the actual and expected
        assertThat(actual, containsInAnyOrder(expected.toArray()));
        assertThat(expected, containsInAnyOrder(actual.toArray()));

        // Test that the URI for 'tenant' is correct in each item
        for (DtoTenant t : actual) {

            // Construct the expected object
            DtoTenant expectedTenant = getExpectedTenant(app.getUri(),
                    app.getTenants(),
                    t.getId());

            // Get the actual object
            actualRaw = dtoResource.getAndVerifyOk(t.getUri(),
                    VendorMediaType.APPLICATION_TENANT_JSON,
                    String.class);
            DtoTenant actualTenant = FuncTest.objectMapper.readValue(
                    actualRaw, DtoTenant.class);

            // Compare
            assertThat(actualTenant, equalTo(expectedTenant));
        }
    }
}
