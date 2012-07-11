/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_TENANT_COLLECTION_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_TENANT_JSON;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import com.midokura.midolman.mgmt.data.dto.client.DtoApplication;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

@RunWith(Enclosed.class)
public class TestTenant {

    public static class TestTenantCrud extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;

        public TestTenantCrud() {
            super(FuncTest.appDesc);
        }

        @Before
        public void before() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Just create an application
            topology = new Topology.Builder(dtoResource).build();

        }

        @Test
        public void testCreateGetListDelete() {

            DtoApplication app = topology.getApplication();

            // Create a tenant with no ID
            DtoTenant tenant1 = new DtoTenant();
            tenant1 = dtoResource.postAndVerifyCreated(app.getTenants(),
                    APPLICATION_TENANT_JSON, tenant1, DtoTenant.class);
            assertNotNull(tenant1.getId());

            // Create a tenant with ID
            DtoTenant tenant2 = new DtoTenant();
            tenant2.setId("foo");
            tenant2 = dtoResource.postAndVerifyCreated(app.getTenants(),
                    APPLICATION_TENANT_JSON, tenant2, DtoTenant.class);
            assertEquals("foo", tenant2.getId());

            // List tenants
            DtoTenant[] tenants = dtoResource.getAndVerifyOk(app.getTenants(),
                    APPLICATION_TENANT_COLLECTION_JSON, DtoTenant[].class);
            assertEquals(2, tenants.length);

            // Delete the first tenant
            dtoResource.deleteAndVerifyNoContent(tenant1.getUri(),
                    APPLICATION_TENANT_JSON);

            // List again
            tenants = dtoResource.getAndVerifyOk(app.getTenants(),
                    APPLICATION_TENANT_COLLECTION_JSON, DtoTenant[].class);
            assertEquals(1, tenants.length);

            // The first tenant should be gone
            dtoResource.getAndVerifyNotFound(tenant1.getUri(),
                    APPLICATION_TENANT_JSON);
        }
    }
}