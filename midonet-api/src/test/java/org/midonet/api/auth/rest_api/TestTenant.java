/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.auth.rest_api;

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
import org.midonet.client.VendorMediaType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests tenant API operations.  Currently assumes MockAuth is being used.
 */
public class TestTenant extends JerseyTest {

    private Topology topology;
    private List<DtoTenant> expected;
    private DtoWebResource dtoResource;

    public TestTenant() {
        super(FuncTest.appDesc);
    }

    private void addBridgesWithTenants(int count) {
        Topology.Builder builder = new Topology.Builder(dtoResource);

        for (int i = 0 ; i < count ; i++) {
            DtoBridge bridge = new DtoBridge();
            String tenantId = Integer.toString(i);
            bridge.setName("foo");
            bridge.setTenantId(tenantId);
            builder.create(tenantId, bridge);
        }

        topology = builder.build();
    }

    private static List<DtoTenant> getTenantList(int startTenantId,
                                                 int endTenantId) {

        List<DtoTenant> tenants = new ArrayList<DtoTenant>();

        for (int i = startTenantId; i <= endTenantId; i++) {
            String tenantId = Integer.toString(i);
            tenants.add(new DtoTenant(tenantId, tenantId));
        }

        return tenants;
    }

    @Before
    public void setUp() {

        WebResource resource = resource();
        dtoResource = new DtoWebResource(resource);

        addBridgesWithTenants(10);
    }

    @Test
    public void testListTenants() throws Exception {

        List<DtoTenant> expected = getTenantList(0, 9);

        DtoApplication app = topology.getApplication();
        DtoTenant[] actual = dtoResource.getAndVerifyOk(app.getTenants(),
                VendorMediaType.APPLICATION_TENANT_COLLECTION_JSON,
                DtoTenant[].class);

        assertEquals(expected.size(), actual.length);
        for (DtoTenant tenant : actual) {
            assertTrue(expected.contains(tenant));
        }
    }
}
