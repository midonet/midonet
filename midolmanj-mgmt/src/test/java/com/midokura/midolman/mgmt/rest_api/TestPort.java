/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.Status.OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

public class TestPort extends JerseyTest {

    DtoRouter router1;
    DtoRouter router2;
    DtoBridge bridge1;
    DtoLogicalRouterPort router1Port1;
    DtoLogicalRouterPort router1Port2;
    DtoLogicalRouterPort router2Port1;
    DtoLogicalRouterPort router2Port2;
    DtoLogicalBridgePort bridge1Port1;
    DtoLogicalBridgePort bridge1Port2;

    BridgeWebResource bridgeResource;
    PortWebResource portResource;
    RouterWebResource routerResource;
    TenantWebResource tenantResource;

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

        // Create a tenant
        DtoTenant tenant = new DtoTenant();
        tenant.setId("tenant-id");
        URI uri = tenantResource.createTenant(tenant);
        tenant = tenantResource.getTenant(uri);

        // Create router 1
        DtoRouter router = new DtoRouter();
        router.setName("router1");
        router.setId(UUID.randomUUID());
        uri = routerResource.createRouter(tenant.getRouters(), router);
        router1 = routerResource.getRouter(uri, OK.getStatusCode());

        // Create router 2
        router = new DtoRouter();
        router.setName("router2");
        router.setId(UUID.randomUUID());
        uri = routerResource.createRouter(tenant.getRouters(), router);
        router2 = routerResource.getRouter(uri, OK.getStatusCode());

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
        portResource.unlinkPorts(router1Port1.getUnlink());
        portResource.unlinkPorts(router1Port2.getUnlink());
        portResource.unlinkPorts(bridge1Port1.getUnlink());
        portResource.unlinkPorts(bridge1Port2.getUnlink());
        portResource.unlinkPorts(router2Port1.getUnlink());
        portResource.unlinkPorts(router2Port2.getUnlink());

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
    public void testCreateGetListDeleteMaterializedBridgePort() {

        // Create a bridge port.
        DtoBridgePort port = new DtoBridgePort();
        port.setDeviceId(bridge1.getId());
        URI uri = portResource.createMaterializedBridgePort(bridge1.getPorts(),
                port);

        DtoBridgePort createdPort = portResource.getMaterializedBridgePort(uri);
        assertEquals(bridge1.getId(), createdPort.getDeviceId());

        // List ports
        List<DtoBridgePort> ports = portResource.getBridgePorts(bridge1
                .getPorts());
        assertEquals(3, ports.size());

        // Delete the port.
        portResource.deletePort(uri);

        // Make sure it's no longer there
        port = portResource.getMaterializedBridgePort(uri,
                NOT_FOUND.getStatusCode());
        ports = portResource.getBridgePorts(bridge1.getPorts());
        assertEquals(2, ports.size());
        assertFalse(ports.contains(createdPort));
    }

    @Test
    public void testCreateGetListDeleteMaterializedRouterPort() {
        // Create a router port.
        DtoMaterializedRouterPort port = new DtoMaterializedRouterPort();
        port.setNetworkAddress("10.0.0.0");
        port.setNetworkLength(24);
        port.setPortAddress("10.0.0.1");
        port.setLocalNetworkAddress("10.0.0.2");
        port.setLocalNetworkLength(32);
        port.setVifId(UUID.randomUUID());
        URI uri = portResource.createMaterializedRouterPort(router1.getPorts(),
                port);
        DtoMaterializedRouterPort createdPort = portResource
                .getMaterializedRouterPort(uri);

        // List ports
        List<DtoRouterPort> ports = portResource.getRouterPorts(router1
                .getPorts());
        assertEquals(3, ports.size());

        // Delete the port.
        portResource.deletePort(uri);

        // Make sure it's no longer there
        port = portResource.getMaterializedRouterPort(uri,
                NOT_FOUND.getStatusCode());
        ports = portResource.getRouterPorts(router1.getPorts());
        assertEquals(2, ports.size());
        assertFalse(ports.contains(createdPort));
    }
}