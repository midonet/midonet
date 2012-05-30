/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.mgmt.data.dto.client.DtoBridge;
import com.midokura.midolman.mgmt.data.dto.client.DtoBridgePort;
import com.midokura.midolman.mgmt.data.dto.client.DtoLogicalBridgePort;
import com.midokura.midolman.mgmt.data.dto.client.DtoLogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoMaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoRouter;
import com.midokura.midolman.mgmt.data.dto.client.DtoRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoRuleChain;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

public class TestPort extends JerseyTest {

    DtoRouter router1;
    DtoRouter router2;
    DtoBridge bridge1;
    DtoRuleChain chain1;
    DtoRuleChain chain2;
    DtoLogicalRouterPort router1Port1;
    DtoLogicalRouterPort router1Port2;
    DtoMaterializedRouterPort router1Port3;
    DtoLogicalRouterPort router2Port1;
    DtoLogicalRouterPort router2Port2;
    DtoLogicalBridgePort bridge1Port1;
    DtoLogicalBridgePort bridge1Port2;
    DtoBridgePort bridge1Port3;

    BridgeWebResource bridgeResource;
    PortWebResource portResource;
    RouterWebResource routerResource;
    TenantWebResource tenantResource;
    ChainWebResource chainResource;

    public TestPort() {
        super(FuncTest.appDesc);
    }

    @Before
    public void before() {

        WebResource resource = resource();
        bridgeResource = new BridgeWebResource(resource);
        portResource = new PortWebResource(resource);
        routerResource = new RouterWebResource(resource);
        tenantResource = new TenantWebResource(resource);
        chainResource = new ChainWebResource(resource);

        // Create a tenant
        DtoTenant tenant = new DtoTenant();
        tenant.setId("tenant-id");
        URI uri = tenantResource.createTenant(tenant);
        tenant = tenantResource.getTenant(uri);
        // TODO: verify creation

        // Prepare chains that can be used to set to port
        DtoRuleChain chain = new DtoRuleChain();
        chain.setName("chain1");
        chain.setTenantId(tenant.getId());
        uri = chainResource.createChain(tenant.getChains(), chain);
        chain1 = chainResource.getChain(uri);

        chain = new DtoRuleChain();
        chain.setName("chain2");
        chain.setTenantId(tenant.getId());
        uri = chainResource.createChain(tenant.getChains(), chain);
        chain2 = chainResource.getChain(uri);

        // Create router 1
        DtoRouter router = new DtoRouter();
        router.setName("router1");
        router.setId(UUID.randomUUID());
        uri = routerResource.createRouter(tenant.getRouters(), router);
        router1 = routerResource.getRouter(uri);

        // Create router 2
        router = new DtoRouter();
        router.setName("router2");
        router.setId(UUID.randomUUID());
        uri = routerResource.createRouter(tenant.getRouters(), router);
        router2 = routerResource.getRouter(uri);

        // Create a bridge.
        DtoBridge bridge = new DtoBridge();
        bridge.setName("bridge1");
        bridge.setId(UUID.randomUUID());
        uri = bridgeResource.createBridge(tenant.getBridges(), bridge);
        bridge1 = bridgeResource.getBridge(uri);

        // Create a logical port on router1
        DtoLogicalRouterPort logicalRouterPort = new DtoLogicalRouterPort();
        logicalRouterPort.setDeviceId(router1.getId());
        logicalRouterPort.setNetworkAddress("10.0.0.0");
        logicalRouterPort.setNetworkLength(24);
        logicalRouterPort.setPortAddress("10.0.0.1");
        uri = portResource.createLogicalRouterPort(router1.getPorts(),
                logicalRouterPort);
        router1Port1 = portResource.getLogicalRouterPort(uri);
        portResource.verifyNoLink(router1Port1);

        // Create another logical port on router1
        logicalRouterPort = new DtoLogicalRouterPort();
        logicalRouterPort.setDeviceId(router1.getId());
        logicalRouterPort.setNetworkAddress("192.168.0.0");
        logicalRouterPort.setNetworkLength(24);
        logicalRouterPort.setPortAddress("192.168.0.1");

        uri = portResource.createLogicalRouterPort(router1.getPorts(),
                logicalRouterPort);
        router1Port2 = portResource.getLogicalRouterPort(uri);
        portResource.verifyNoLink(router1Port2);

        // Create a materialized port on router 1
        DtoMaterializedRouterPort matRouterPort = new DtoMaterializedRouterPort();
        matRouterPort.setNetworkAddress("10.0.0.0");
        matRouterPort.setNetworkLength(24);
        matRouterPort.setPortAddress("10.0.0.1");
        matRouterPort.setLocalNetworkAddress("10.0.0.2");
        matRouterPort.setLocalNetworkLength(32);
        matRouterPort.setVifId(UUID.randomUUID());
        matRouterPort.setInboundFilter(chain1.getId());
        matRouterPort.setOutboundFilter(chain2.getId());
        uri = portResource.createMaterializedRouterPort(router1.getPorts(),
                matRouterPort);
        router1Port3 = portResource.getMaterializedRouterPort(uri);

        // Create a logical port on router2
        logicalRouterPort = new DtoLogicalRouterPort();
        logicalRouterPort.setDeviceId(router2.getId());
        logicalRouterPort.setNetworkAddress("10.0.1.0");
        logicalRouterPort.setNetworkLength(24);
        logicalRouterPort.setPortAddress("10.0.1.1");
        uri = portResource.createLogicalRouterPort(router2.getPorts(),
                logicalRouterPort);
        router2Port1 = portResource.getLogicalRouterPort(uri);
        portResource.verifyNoLink(router2Port1);

        // Create another logical port on router2
        logicalRouterPort = new DtoLogicalRouterPort();
        logicalRouterPort.setDeviceId(router2.getId());
        logicalRouterPort.setNetworkAddress("192.168.1.0");
        logicalRouterPort.setNetworkLength(24);
        logicalRouterPort.setPortAddress("192.168.1.1");
        uri = portResource.createLogicalRouterPort(router2.getPorts(),
                logicalRouterPort);
        router2Port2 = portResource.getLogicalRouterPort(uri);
        portResource.verifyNoLink(router2Port2);

        // Create a logical bridge link
        DtoLogicalBridgePort logicalBridgePort = new DtoLogicalBridgePort();
        logicalBridgePort.setDeviceId(bridge1.getId());
        uri = portResource.createLogicalBridgePort(bridge1.getPorts(),
                logicalBridgePort);
        bridge1Port1 = portResource.getLogicalBridgePort(uri);
        portResource.verifyNoLink(bridge1Port1);

        // Create another logical bridge link
        logicalBridgePort = new DtoLogicalBridgePort();
        logicalBridgePort.setDeviceId(bridge1.getId());
        uri = portResource.createLogicalBridgePort(bridge1.getPorts(),
                logicalBridgePort);
        bridge1Port2 = portResource.getLogicalBridgePort(uri);
        portResource.verifyNoLink(bridge1Port2);

        // Create a materialized port on bridge 1
        DtoBridgePort matBridgePort = new DtoBridgePort();
        matBridgePort.setDeviceId(bridge1.getId());
        matBridgePort.setInboundFilter(chain1.getId());
        matBridgePort.setOutboundFilter(chain2.getId());
        uri = portResource.createMaterializedBridgePort(bridge1.getPorts(),
                matBridgePort);
        bridge1Port3 = portResource.getMaterializedBridgePort(uri);

        // Link router1 and router2
        portResource.linkPorts(router1Port1.getLink(), router2Port1.getId());

        // Link router1 and bridge1
        portResource.linkPorts(router1Port2.getLink(), bridge1Port1.getId());

        // Link bridge1 and router2
        portResource.linkPorts(bridge1Port2.getLink(), router2Port2.getId());

        // Get all the ports again and verify that they are linked
        router1Port1 = portResource.getLogicalRouterPort(router1Port1.getUri());
        router1Port2 = portResource.getLogicalRouterPort(router1Port2.getUri());
        router2Port1 = portResource.getLogicalRouterPort(router2Port1.getUri());
        router2Port2 = portResource.getLogicalRouterPort(router2Port2.getUri());
        bridge1Port1 = portResource.getLogicalBridgePort(bridge1Port1.getUri());
        bridge1Port2 = portResource.getLogicalBridgePort(bridge1Port2.getUri());

        portResource.verifyLink(router1Port1, router2Port1.getId());
        portResource.verifyLink(router1Port2, bridge1Port1.getId());
        portResource.verifyLink(router2Port1, router1Port1.getId());
        portResource.verifyLink(router2Port2, bridge1Port2.getId());
        portResource.verifyLink(bridge1Port1, router1Port2.getId());
        portResource.verifyLink(bridge1Port2, router2Port2.getId());

    }

    @Test
    public void testRemoveAndAddRouterChains() {

        assertEquals(chain1.getId(), router1Port3.getInboundFilter());
        assertEquals(chain2.getId(), router1Port3.getOutboundFilter());
        router1Port3.setInboundFilter(null);
        router1Port3.setOutboundFilter(null);
        portResource.updatePort(router1Port3.getUri(), router1Port3);
        router1Port3 = portResource.getMaterializedRouterPort(router1Port3
                .getUri());
        assertNull(router1Port3.getInboundFilter());
        assertNull(router1Port3.getOutboundFilter());

        router1Port3.setInboundFilter(chain2.getId());
        router1Port3.setOutboundFilter(chain1.getId());
        portResource.updatePort(router1Port3.getUri(), router1Port3);
        router1Port3 = portResource.getMaterializedRouterPort(router1Port3
                .getUri());
        assertEquals(chain2.getId(), router1Port3.getInboundFilter());
        assertEquals(chain1.getId(), router1Port3.getOutboundFilter());
    }

    @Test
    public void testRemoveAndAddBridgeChains() {

        assertEquals(chain1.getId(), bridge1Port3.getInboundFilter());
        assertEquals(chain2.getId(), bridge1Port3.getOutboundFilter());
        bridge1Port3.setInboundFilter(null);
        bridge1Port3.setOutboundFilter(null);
        portResource.updatePort(bridge1Port3.getUri(), bridge1Port3);
        bridge1Port3 = portResource.getMaterializedBridgePort(bridge1Port3
                .getUri());
        assertNull(bridge1Port3.getInboundFilter());
        assertNull(bridge1Port3.getOutboundFilter());

        bridge1Port3.setInboundFilter(chain2.getId());
        bridge1Port3.setOutboundFilter(chain1.getId());
        portResource.updatePort(bridge1Port3.getUri(), bridge1Port3);
        bridge1Port3 = portResource.getMaterializedBridgePort(bridge1Port3
                .getUri());
        assertEquals(chain2.getId(), bridge1Port3.getInboundFilter());
        assertEquals(chain1.getId(), bridge1Port3.getOutboundFilter());
    }

    @Test
    public void testUpdateRouterChains() {

        // Right now we allow directly updating chains
        assertEquals(chain1.getId(), router1Port3.getInboundFilter());
        assertEquals(chain2.getId(), router1Port3.getOutboundFilter());
        router1Port3.setInboundFilter(chain2.getId());
        router1Port3.setOutboundFilter(chain1.getId());
        portResource.updatePort(router1Port3.getUri(), router1Port3);
        router1Port3 = portResource.getMaterializedRouterPort(router1Port3
                .getUri());
        assertEquals(chain2.getId(), router1Port3.getInboundFilter());
        assertEquals(chain1.getId(), router1Port3.getOutboundFilter());
    }

    @Test
    public void testUpdateBridgeChains() {

        // Right now we allow directly updating chains
        assertEquals(chain1.getId(), bridge1Port3.getInboundFilter());
        assertEquals(chain2.getId(), bridge1Port3.getOutboundFilter());
        bridge1Port3.setInboundFilter(chain2.getId());
        bridge1Port3.setOutboundFilter(chain1.getId());
        portResource.updatePort(bridge1Port3.getUri(), bridge1Port3);
        bridge1Port3 = portResource.getMaterializedBridgePort(bridge1Port3
                .getUri());
        assertEquals(chain2.getId(), bridge1Port3.getInboundFilter());
        assertEquals(chain1.getId(), bridge1Port3.getOutboundFilter());
    }

    @Test
    public void testCannotLinkAlreadyLinkedPorts() {
        portResource.linkPorts(router1Port1.getLink(), router2Port1.getId(),
                BAD_REQUEST.getStatusCode());
        portResource.linkPorts(router1Port1.getLink(), bridge1Port2.getId(),
                BAD_REQUEST.getStatusCode());
    }

    @Test
    public void testCannotDeleteLinkedPort() {
        portResource.deletePort(router1Port1.getUri(),
                BAD_REQUEST.getStatusCode());
        portResource.deletePort(bridge1Port1.getUri(),
                BAD_REQUEST.getStatusCode());
    }

    @Test
    public void testUnlinkAndDelete() {

        // Unlinking multiple times should not cause an error
        portResource.unlinkPorts(router1Port1.getLink());
        portResource.unlinkPorts(router1Port2.getLink());
        portResource.unlinkPorts(bridge1Port1.getLink());
        portResource.unlinkPorts(bridge1Port2.getLink());
        portResource.unlinkPorts(router2Port1.getLink());
        portResource.unlinkPorts(router2Port2.getLink());

        // Delete all the ports
        portResource.deletePort(router1Port1.getUri());
        portResource.deletePort(router1Port2.getUri());
        portResource.deletePort(router2Port1.getUri());
        portResource.deletePort(router2Port2.getUri());
        portResource.deletePort(bridge1Port1.getUri());
        portResource.deletePort(bridge1Port2.getUri());
    }

    @Test
    public void testGetPeerPorts() {

        // Get the peers of router1
        List<DtoPort> ports = portResource.getPeerPorts(router1.getPeerPorts());
        assertNotNull(ports);
        assertEquals(2, ports.size());
        assertTrue(ports.contains(router2Port1));
        assertTrue(ports.contains(bridge1Port1));

        // Get the peers of router2
        ports = portResource.getPeerPorts(router2.getPeerPorts());
        assertNotNull(ports);
        assertEquals(2, ports.size());
        assertTrue(ports.contains(router1Port1));
        assertTrue(ports.contains(bridge1Port2));

        // Get the peers of bridge1
        ports = portResource.getPeerPorts(bridge1.getPeerPorts());
        assertNotNull(ports);
        assertEquals(2, ports.size());
        assertTrue(ports.contains(router1Port2));
        assertTrue(ports.contains(router2Port2));
    }

    @Test
    public void testListDeleteMaterializedBridgePort() {

        // List ports
        List<DtoBridgePort> ports = portResource.getBridgePorts(bridge1
                .getPorts());
        assertEquals(3, ports.size());

        // Delete the port.
        portResource.deletePort(bridge1Port3.getUri());

        // Make sure it's no longer there
        DtoBridgePort port = portResource.getMaterializedBridgePort(
                bridge1Port3.getUri(), NOT_FOUND.getStatusCode());
        ports = portResource.getBridgePorts(bridge1.getPorts());
        assertEquals(2, ports.size());
        assertFalse(ports.contains(port));
    }

    @Test
    public void testListDeleteMaterializedRouterPort() {

        // List ports
        List<DtoRouterPort> ports = portResource.getRouterPorts(router1
                .getPorts());
        assertEquals(3, ports.size());

        // Delete the port.
        portResource.deletePort(router1Port3.getUri());

        // Make sure it's no longer there
        DtoMaterializedRouterPort port = portResource
                .getMaterializedRouterPort(router1Port3.getUri(),
                        NOT_FOUND.getStatusCode());
        ports = portResource.getRouterPorts(router1.getPorts());
        assertEquals(2, ports.size());
        assertFalse(ports.contains(port));
    }
}