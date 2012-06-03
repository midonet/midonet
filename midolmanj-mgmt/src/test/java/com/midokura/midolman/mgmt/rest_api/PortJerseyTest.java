/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_BRIDGE_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_CHAIN_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_PORT_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_ROUTER_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_TENANT_JSON;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.Response;

import org.junit.Before;

import com.midokura.midolman.mgmt.data.dto.client.DtoBridge;
import com.midokura.midolman.mgmt.data.dto.client.DtoBridgePort;
import com.midokura.midolman.mgmt.data.dto.client.DtoLogicalBridgePort;
import com.midokura.midolman.mgmt.data.dto.client.DtoLogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoMaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoRouter;
import com.midokura.midolman.mgmt.data.dto.client.DtoRuleChain;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

public abstract class PortJerseyTest extends JerseyTest {

    protected DtoWebResource dtoResource;
    protected DtoRouter router1;
    protected DtoRouter router2;
    protected DtoBridge bridge1;
    protected DtoRuleChain chain1;
    protected DtoRuleChain chain2;
    protected DtoLogicalRouterPort router1LogPort1;
    protected DtoLogicalRouterPort router1LogPort2;
    protected DtoMaterializedRouterPort router1MatPort1;
    protected DtoLogicalRouterPort router2LogPort1;
    protected DtoLogicalRouterPort router2LogPort2;
    protected DtoLogicalBridgePort bridge1LogPort1;
    protected DtoLogicalBridgePort bridge1LogPort2;
    protected DtoBridgePort bridge1MatPort1;

    public PortJerseyTest() {
        super(FuncTest.appDesc);
    }

    @Before
    public void setUp() {

        WebResource resource = resource();
        dtoResource = new DtoWebResource(resource);

        // create a tenant
        URI tenantUri = dtoResource.getWebResource().path("tenants").getURI();
        DtoTenant tenant = new DtoTenant();
        tenant.setId("tenant-id");
        URI uri = dtoResource.post(tenantUri, APPLICATION_TENANT_JSON, tenant);
        tenant = dtoResource.get(uri, APPLICATION_TENANT_JSON, DtoTenant.class);

        // create router1
        DtoRouter router = new DtoRouter();
        router.setName("router1");
        router.setId(UUID.randomUUID());
        uri = dtoResource.post(tenant.getRouters(), APPLICATION_ROUTER_JSON,
                router);
        router1 = dtoResource
                .get(uri, APPLICATION_ROUTER_JSON, DtoRouter.class);

        // create router2
        router = new DtoRouter();
        router.setName("router2");
        router.setId(UUID.randomUUID());
        uri = dtoResource.post(tenant.getRouters(), APPLICATION_ROUTER_JSON,
                router);
        router2 = dtoResource
                .get(uri, APPLICATION_ROUTER_JSON, DtoRouter.class);

        // create a bridge.
        DtoBridge bridge = new DtoBridge();
        bridge.setName("bridge1");
        bridge.setId(UUID.randomUUID());
        uri = dtoResource.post(tenant.getBridges(), APPLICATION_BRIDGE_JSON,
                bridge);
        bridge1 = dtoResource
                .get(uri, APPLICATION_BRIDGE_JSON, DtoBridge.class);

        // Prepare chains that can be used to set to port
        DtoRuleChain chain = new DtoRuleChain();
        chain.setName("chain1");
        chain.setTenantId(tenant.getId());
        uri = dtoResource.post(tenant.getChains(), APPLICATION_CHAIN_JSON,
                chain);
        chain1 = dtoResource.get(uri, APPLICATION_CHAIN_JSON,
                DtoRuleChain.class);

        chain = new DtoRuleChain();
        chain.setName("chain2");
        chain.setTenantId(tenant.getId());
        uri = dtoResource.post(tenant.getChains(), APPLICATION_CHAIN_JSON,
                chain);
        chain2 = dtoResource.get(uri, APPLICATION_CHAIN_JSON,
                DtoRuleChain.class);

        // create one logical port
        DtoLogicalRouterPort logicalRouterPort = new DtoLogicalRouterPort();
        logicalRouterPort.setDeviceId(router.getId());
        logicalRouterPort.setNetworkAddress("10.0.0.0");
        logicalRouterPort.setNetworkLength(24);
        logicalRouterPort.setPortAddress("10.0.0.1");
        uri = dtoResource.post(router1.getPorts(), APPLICATION_PORT_JSON,
                logicalRouterPort);
        router1LogPort1 = dtoResource.get(uri, APPLICATION_PORT_JSON,
                DtoLogicalRouterPort.class);

        // create another logical port on router1
        logicalRouterPort = new DtoLogicalRouterPort();
        logicalRouterPort.setDeviceId(router1.getId());
        logicalRouterPort.setNetworkAddress("192.168.0.0");
        logicalRouterPort.setNetworkLength(24);
        logicalRouterPort.setPortAddress("192.168.0.1");
        uri = dtoResource.post(router1.getPorts(), APPLICATION_PORT_JSON,
                logicalRouterPort);
        router1LogPort2 = dtoResource.get(uri, APPLICATION_PORT_JSON,
                DtoLogicalRouterPort.class);

        // create a materialized port
        DtoMaterializedRouterPort matRouterPort = new DtoMaterializedRouterPort();
        matRouterPort.setNetworkAddress("10.0.0.0");
        matRouterPort.setNetworkLength(24);
        matRouterPort.setPortAddress("10.0.0.1");
        matRouterPort.setLocalNetworkAddress("10.0.0.2");
        matRouterPort.setLocalNetworkLength(32);
        matRouterPort.setVifId(UUID.randomUUID());
        matRouterPort.setInboundFilterId(chain1.getId());
        matRouterPort.setOutboundFilterId(chain2.getId());
        uri = dtoResource.post(router1.getPorts(), APPLICATION_PORT_JSON,
                matRouterPort);
        router1MatPort1 = dtoResource.get(uri, APPLICATION_PORT_JSON,
                DtoMaterializedRouterPort.class);

        // create a logical port on router2
        logicalRouterPort = new DtoLogicalRouterPort();
        logicalRouterPort.setDeviceId(router2.getId());
        logicalRouterPort.setNetworkAddress("10.0.1.0");
        logicalRouterPort.setNetworkLength(24);
        logicalRouterPort.setPortAddress("10.0.1.1");
        uri = dtoResource.post(router2.getPorts(), APPLICATION_PORT_JSON,
                logicalRouterPort);
        router2LogPort1 = dtoResource.get(uri, APPLICATION_PORT_JSON,
                DtoLogicalRouterPort.class);

        // create another logical port on router2
        logicalRouterPort = new DtoLogicalRouterPort();
        logicalRouterPort.setDeviceId(router2.getId());
        logicalRouterPort.setNetworkAddress("192.168.1.0");
        logicalRouterPort.setNetworkLength(24);
        logicalRouterPort.setPortAddress("192.168.1.1");
        uri = dtoResource.post(router2.getPorts(), APPLICATION_PORT_JSON,
                logicalRouterPort);
        router2LogPort2 = dtoResource.get(uri, APPLICATION_PORT_JSON,
                DtoLogicalRouterPort.class);

        // create a logical bridge link
        DtoLogicalBridgePort logicalBridgePort = new DtoLogicalBridgePort();
        logicalBridgePort.setDeviceId(bridge1.getId());
        uri = dtoResource.post(bridge1.getPorts(), APPLICATION_PORT_JSON,
                logicalBridgePort);
        bridge1LogPort1 = dtoResource.get(uri, APPLICATION_PORT_JSON,
                DtoLogicalBridgePort.class);

        // create another logical bridge link
        logicalBridgePort = new DtoLogicalBridgePort();
        logicalBridgePort.setDeviceId(bridge1.getId());
        uri = dtoResource.post(bridge1.getPorts(), APPLICATION_PORT_JSON,
                logicalBridgePort);
        bridge1LogPort2 = dtoResource.get(uri, APPLICATION_PORT_JSON,
                DtoLogicalBridgePort.class);

        // create a materialized port on bridge 1
        DtoBridgePort matBridgePort = new DtoBridgePort();
        matBridgePort.setDeviceId(bridge1.getId());
        matBridgePort.setInboundFilterId(chain1.getId());
        matBridgePort.setOutboundFilterId(chain2.getId());
        uri = dtoResource.post(bridge1.getPorts(), APPLICATION_PORT_JSON,
                matBridgePort);
        bridge1MatPort1 = dtoResource.get(uri, APPLICATION_PORT_JSON,
                DtoBridgePort.class);

        // Link router1 and router2
        dtoResource.post(router1LogPort1.getLink(), APPLICATION_PORT_JSON,
                "{\"peerId\": \"" + router2LogPort1.getId() + "\"}",
                Response.Status.NO_CONTENT.getStatusCode());

        // Link router1 and bridge1
        dtoResource.post(router1LogPort2.getLink(), APPLICATION_PORT_JSON,
                "{\"peerId\": \"" + bridge1LogPort1.getId() + "\"}",
                Response.Status.NO_CONTENT.getStatusCode());

        // Link bridge1 and router2
        dtoResource.post(bridge1LogPort2.getLink(), APPLICATION_PORT_JSON,
                "{\"peerId\": \"" + router2LogPort2.getId() + "\"}",
                Response.Status.NO_CONTENT.getStatusCode());
    }

}
