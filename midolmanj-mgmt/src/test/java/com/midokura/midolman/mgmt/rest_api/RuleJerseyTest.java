/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_CHAIN_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_PORT_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_ROUTER_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_TENANT_JSON;

import java.net.URI;
import java.util.UUID;

import org.junit.Before;

import com.midokura.midolman.mgmt.data.dto.client.DtoLogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoMaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoRouter;
import com.midokura.midolman.mgmt.data.dto.client.DtoRuleChain;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

public abstract class RuleJerseyTest extends JerseyTest {

    protected DtoWebResource dtoResource;
    protected DtoMaterializedRouterPort router1MatPort1;
    protected DtoLogicalRouterPort router1LogPort1;
    protected DtoRuleChain chain1;

    public RuleJerseyTest() {
        super(FuncTest.appDesc);
    }

    @Before
    public void setUp() {

        WebResource resource = resource();
        dtoResource = new DtoWebResource(resource);

        // Create a tenant
        URI tenantUri = dtoResource.getWebResource().path("tenants").getURI();
        DtoTenant tenant = new DtoTenant();
        tenant.setId("tenant-id");
        URI uri = dtoResource.post(tenantUri, APPLICATION_TENANT_JSON, tenant);
        tenant = dtoResource.get(uri, APPLICATION_TENANT_JSON, DtoTenant.class);

        // Create a router
        DtoRouter router = new DtoRouter();
        router.setName("router1");
        router.setId(UUID.randomUUID());
        uri = dtoResource.post(tenant.getRouters(), APPLICATION_ROUTER_JSON,
                router);
        router = dtoResource.get(uri, APPLICATION_ROUTER_JSON, DtoRouter.class);

        // Create a chain
        DtoRuleChain chain = new DtoRuleChain();
        chain.setName("chain1");
        chain.setTenantId(tenant.getId());
        uri = dtoResource.post(tenant.getChains(), APPLICATION_CHAIN_JSON,
                chain);
        chain1 = dtoResource.get(uri, APPLICATION_CHAIN_JSON,
                DtoRuleChain.class);

        // Create one materialized port
        DtoMaterializedRouterPort matRouterPort = new DtoMaterializedRouterPort();
        matRouterPort.setNetworkAddress("10.0.0.0");
        matRouterPort.setNetworkLength(24);
        matRouterPort.setPortAddress("10.0.0.1");
        matRouterPort.setLocalNetworkAddress("10.0.0.2");
        matRouterPort.setLocalNetworkLength(32);
        matRouterPort.setVifId(UUID.randomUUID());
        matRouterPort.setInboundFilterId(chain.getId());
        matRouterPort.setOutboundFilterId(chain.getId());
        uri = dtoResource.post(router.getPorts(), APPLICATION_PORT_JSON,
                matRouterPort);
        router1MatPort1 = dtoResource.get(uri, APPLICATION_PORT_JSON,
                DtoMaterializedRouterPort.class);

        // Create one logical port
        DtoLogicalRouterPort logicalRouterPort = new DtoLogicalRouterPort();
        logicalRouterPort.setDeviceId(router.getId());
        logicalRouterPort.setNetworkAddress("10.0.0.0");
        logicalRouterPort.setNetworkLength(24);
        logicalRouterPort.setPortAddress("10.0.0.1");
        uri = dtoResource.post(router.getPorts(), APPLICATION_PORT_JSON,
                logicalRouterPort);
        router1LogPort1 = dtoResource.get(uri, APPLICATION_PORT_JSON,
                DtoLogicalRouterPort.class);
    }

}
