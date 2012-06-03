/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_PORT_JSON;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.UUID;

import javax.ws.rs.core.Response;

import org.junit.Test;

import com.midokura.midolman.mgmt.data.dto.client.DtoBridgePort;
import com.midokura.midolman.mgmt.data.dto.client.DtoMaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoRouterPort;

public class TestPort extends PortJerseyTest {

    @Test
    public void testUpdateVif() {

        assertNull(router1MatPort1.getVifId());
        UUID vifId = UUID.randomUUID();
        router1MatPort1.setVifId(vifId);
        dtoResource.put(router1MatPort1.getUri(),
                APPLICATION_PORT_JSON, router1MatPort1);
        router1MatPort1 = dtoResource.get(
                router1MatPort1.getUri(), APPLICATION_PORT_JSON,
                DtoMaterializedRouterPort.class);
        assertEquals(vifId, router1MatPort1.getVifId());

        router1MatPort1.setVifId(null);
        dtoResource.put(router1MatPort1.getUri(),
                APPLICATION_PORT_JSON, router1MatPort1);
        router1MatPort1 = dtoResource.get(
                router1MatPort1.getUri(), APPLICATION_PORT_JSON,
                DtoMaterializedRouterPort.class);
        assertNull(router1MatPort1.getVifId());

        assertNull(bridge1MatPort1.getVifId());
        vifId = UUID.randomUUID();
        bridge1MatPort1.setVifId(vifId);
        dtoResource.put(bridge1MatPort1.getUri(),
                APPLICATION_PORT_JSON, bridge1MatPort1);
        bridge1MatPort1 = dtoResource.get(
                bridge1MatPort1.getUri(), APPLICATION_PORT_JSON,
                DtoBridgePort.class);
        assertEquals(vifId, bridge1MatPort1.getVifId());

        bridge1MatPort1.setVifId(null);
        dtoResource.put(bridge1MatPort1.getUri(),
                APPLICATION_PORT_JSON, bridge1MatPort1);
        bridge1MatPort1 = dtoResource.get(
                bridge1MatPort1.getUri(), APPLICATION_PORT_JSON,
                DtoBridgePort.class);
        assertNull(bridge1MatPort1.getVifId());
    }

    @Test
    public void testRemoveAndAddRouterChains() {

        assertEquals(chain1.getId(),
                router1MatPort1.getInboundFilterId());
        assertEquals(chain2.getId(),
                router1MatPort1.getOutboundFilterId());
        router1MatPort1.setInboundFilterId(null);
        router1MatPort1.setOutboundFilterId(null);
        dtoResource.put(router1MatPort1.getUri(),
                APPLICATION_PORT_JSON, router1MatPort1);
        router1MatPort1 = dtoResource.get(
                router1MatPort1.getUri(), APPLICATION_PORT_JSON,
                DtoMaterializedRouterPort.class);
        assertNull(router1MatPort1.getInboundFilterId());
        assertNull(router1MatPort1.getOutboundFilterId());

        router1MatPort1.setInboundFilterId(chain2.getId());
        router1MatPort1.setOutboundFilterId(chain1.getId());
        dtoResource.put(router1MatPort1.getUri(),
                APPLICATION_PORT_JSON, router1MatPort1);
        router1MatPort1 = dtoResource.get(
                router1MatPort1.getUri(), APPLICATION_PORT_JSON,
                DtoMaterializedRouterPort.class);
        assertEquals(chain2.getId(),
                router1MatPort1.getInboundFilterId());
        assertEquals(chain1.getId(),
                router1MatPort1.getOutboundFilterId());
    }

    @Test
    public void testRemoveAndAddBridgeChains() {

        assertEquals(chain1.getId(),
                bridge1MatPort1.getInboundFilterId());
        assertEquals(chain2.getId(),
                bridge1MatPort1.getOutboundFilterId());
        bridge1MatPort1.setInboundFilterId(null);
        bridge1MatPort1.setOutboundFilterId(null);
        dtoResource.put(bridge1MatPort1.getUri(),
                APPLICATION_PORT_JSON, bridge1MatPort1);
        bridge1MatPort1 = dtoResource.get(
                bridge1MatPort1.getUri(), APPLICATION_PORT_JSON,
                DtoBridgePort.class);
        assertNull(bridge1MatPort1.getInboundFilterId());
        assertNull(bridge1MatPort1.getOutboundFilterId());

        bridge1MatPort1.setInboundFilterId(chain2.getId());
        bridge1MatPort1.setOutboundFilterId(chain1.getId());
        dtoResource.put(bridge1MatPort1.getUri(),
                APPLICATION_PORT_JSON, bridge1MatPort1);
        bridge1MatPort1 = dtoResource.get(
                bridge1MatPort1.getUri(), APPLICATION_PORT_JSON,
                DtoBridgePort.class);
        assertEquals(chain2.getId(),
                bridge1MatPort1.getInboundFilterId());
        assertEquals(chain1.getId(),
                bridge1MatPort1.getOutboundFilterId());
    }

    @Test
    public void testUpdateRouterChains() {

        // Right now we allow directly updating chains
        assertEquals(chain1.getId(),
                router1MatPort1.getInboundFilterId());
        assertEquals(chain2.getId(),
                router1MatPort1.getOutboundFilterId());
        router1MatPort1.setInboundFilterId(chain2.getId());
        router1MatPort1.setOutboundFilterId(chain1.getId());
        dtoResource.put(router1MatPort1.getUri(),
                APPLICATION_PORT_JSON, router1MatPort1);
        router1MatPort1 = dtoResource.get(
                router1MatPort1.getUri(), APPLICATION_PORT_JSON,
                DtoMaterializedRouterPort.class);
        assertEquals(chain2.getId(),
                router1MatPort1.getInboundFilterId());
        assertEquals(chain1.getId(),
                router1MatPort1.getOutboundFilterId());
    }

    @Test
    public void testUpdateBridgeChains() {

        // Right now we allow directly updating chains
        assertEquals(chain1.getId(),
                bridge1MatPort1.getInboundFilterId());
        assertEquals(chain2.getId(),
                bridge1MatPort1.getOutboundFilterId());
        bridge1MatPort1.setInboundFilterId(chain2.getId());
        bridge1MatPort1.setOutboundFilterId(chain1.getId());
        dtoResource.put(bridge1MatPort1.getUri(),
                APPLICATION_PORT_JSON, bridge1MatPort1);
        bridge1MatPort1 = dtoResource.get(
                bridge1MatPort1.getUri(), APPLICATION_PORT_JSON,
                DtoBridgePort.class);
        assertEquals(chain2.getId(),
                bridge1MatPort1.getInboundFilterId());
        assertEquals(chain1.getId(),
                bridge1MatPort1.getOutboundFilterId());
    }

    @Test
    public void testCannotLinkAlreadyLinkedPorts() {
        dtoResource.post(router1LogPort1.getLink(),
                APPLICATION_PORT_JSON, "{\"peerId\": \""
                        + router2LogPort1.getId() + "\"}",
                Response.Status.BAD_REQUEST.getStatusCode());
        dtoResource.post(router1LogPort1.getLink(),
                APPLICATION_PORT_JSON, "{\"peerId\": \""
                        + bridge1LogPort2.getId() + "\"}",
                Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test
    public void testCannotDeleteLinkedPort() {
        dtoResource.delete(router1LogPort1.getUri(),
                APPLICATION_PORT_JSON, BAD_REQUEST.getStatusCode());
        dtoResource.delete(bridge1LogPort1.getUri(),
                APPLICATION_PORT_JSON, BAD_REQUEST.getStatusCode());
    }

    @Test
    public void testUnlinkAndDelete() {

        // Unlinking multiple times should not cause an error
        dtoResource.post(router1LogPort1.getLink(),
                APPLICATION_PORT_JSON, "{\"peerId\": " + null + "}",
                Response.Status.NO_CONTENT.getStatusCode());
        dtoResource.post(router1LogPort2.getLink(),
                APPLICATION_PORT_JSON, "{\"peerId\": " + null + "}",
                Response.Status.NO_CONTENT.getStatusCode());
        dtoResource.post(bridge1LogPort1.getLink(),
                APPLICATION_PORT_JSON, "{\"peerId\": " + null + "}",
                Response.Status.NO_CONTENT.getStatusCode());
        dtoResource.post(bridge1LogPort2.getLink(),
                APPLICATION_PORT_JSON, "{\"peerId\": " + null + "}",
                Response.Status.NO_CONTENT.getStatusCode());
        dtoResource.post(router2LogPort1.getLink(),
                APPLICATION_PORT_JSON, "{\"peerId\": " + null + "}",
                Response.Status.NO_CONTENT.getStatusCode());
        dtoResource.post(router2LogPort2.getLink(),
                APPLICATION_PORT_JSON, "{\"peerId\": " + null + "}",
                Response.Status.NO_CONTENT.getStatusCode());

        // Delete all the ports
        dtoResource.delete(router1LogPort1.getUri(),
                APPLICATION_PORT_JSON);
        dtoResource.delete(router1LogPort2.getUri(),
                APPLICATION_PORT_JSON);
        dtoResource.delete(router2LogPort1.getUri(),
                APPLICATION_PORT_JSON);
        dtoResource.delete(router2LogPort2.getUri(),
                APPLICATION_PORT_JSON);
        dtoResource.delete(bridge1LogPort1.getUri(),
                APPLICATION_PORT_JSON);
        dtoResource.delete(bridge1LogPort2.getUri(),
                APPLICATION_PORT_JSON);
    }

    @Test
    public void testGetPeerPorts() {

        // Get the peers of router1
        DtoPort[] ports = dtoResource.get(router1.getPeerPorts(),
                APPLICATION_PORT_JSON, DtoPort[].class);
        assertNotNull(ports);
        assertEquals(2, ports.length);

        // Get the peers of router2
        ports = dtoResource.get(router2.getPeerPorts(),
                APPLICATION_PORT_JSON, DtoPort[].class);
        assertNotNull(ports);
        assertEquals(2, ports.length);

        // Get the peers of bridge1
        ports = dtoResource.get(bridge1.getPeerPorts(),
                APPLICATION_PORT_JSON, DtoPort[].class);
        assertNotNull(ports);
        assertEquals(2, ports.length);
    }

    @Test
    public void testListDeleteMaterializedBridgePort() {

        // List ports
        DtoBridgePort[] ports = dtoResource.get(bridge1.getPorts(),
                APPLICATION_PORT_JSON, DtoBridgePort[].class);
        assertEquals(3, ports.length);

        // Delete the port.
        dtoResource.delete(bridge1MatPort1.getUri(),
                APPLICATION_PORT_JSON);

        // Make sure it's no longer there
        DtoBridgePort port = dtoResource.get(bridge1MatPort1.getUri(),
                APPLICATION_PORT_JSON, DtoBridgePort.class,
                NOT_FOUND.getStatusCode());
        assertNull(port);
        ports = dtoResource.get(bridge1.getPorts(),
                APPLICATION_PORT_JSON, DtoBridgePort[].class);
        assertEquals(2, ports.length);
    }

    @Test
    public void testListDeleteMaterializedRouterPort() {

        // List ports
        DtoRouterPort[] ports = dtoResource.get(router1.getPorts(),
                APPLICATION_PORT_JSON, DtoRouterPort[].class);
        assertEquals(3, ports.length);

        // Delete the port.
        dtoResource.delete(router1MatPort1.getUri(),
                APPLICATION_PORT_JSON);

        // Make sure it's no longer there
        DtoMaterializedRouterPort port = dtoResource.get(
                router1MatPort1.getUri(), APPLICATION_PORT_JSON,
                DtoMaterializedRouterPort.class, NOT_FOUND.getStatusCode());
        assertNull(port);
        ports = dtoResource.get(router1.getPorts(),
                APPLICATION_PORT_JSON, DtoRouterPort[].class);
        assertEquals(2, ports.length);
    }
}