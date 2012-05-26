/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_PORT_COLLECTION_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_PORT_JSON;
import static javax.ws.rs.core.Response.Status.CREATED;
import static javax.ws.rs.core.Response.Status.NO_CONTENT;
import static javax.ws.rs.core.Response.Status.OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.client.DtoBridgePort;
import com.midokura.midolman.mgmt.data.dto.client.DtoLogicalBridgePort;
import com.midokura.midolman.mgmt.data.dto.client.DtoLogicalPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoLogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoMaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoRouterPort;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.GenericType;
import com.sun.jersey.api.client.WebResource;

public class PortWebResource {

    private final WebResource resource;

    public PortWebResource(WebResource resource) {
        this.resource = resource;
    }

    public URI createMaterializedRouterPort(URI uri,
            DtoMaterializedRouterPort port) {
        return createMaterializedRouterPort(uri, port, CREATED.getStatusCode());
    }

    public URI createMaterializedRouterPort(URI uri,
            DtoMaterializedRouterPort port, int status) {
        ClientResponse response = resource.uri(uri).type(APPLICATION_PORT_JSON)
                .post(ClientResponse.class, port);
        assertEquals(status, response.getStatus());
        return response.getLocation();
    }

    public DtoMaterializedRouterPort getMaterializedRouterPort(URI uri) {
        return getMaterializedRouterPort(uri, OK.getStatusCode());
    }

    public DtoMaterializedRouterPort getMaterializedRouterPort(URI uri,
            int status) {
        ClientResponse response = resource.uri(uri).type(APPLICATION_PORT_JSON)
                .get(ClientResponse.class);
        assertEquals(status, response.getStatus());
        if (status == OK.getStatusCode()) {
            return response.getEntity(DtoMaterializedRouterPort.class);
        } else {
            return null;
        }
    }

    public URI createLogicalRouterPort(URI uri, DtoLogicalRouterPort port) {
        return createLogicalRouterPort(uri, port, CREATED.getStatusCode());
    }

    public URI createLogicalRouterPort(URI uri, DtoLogicalRouterPort port,
            int status) {
        ClientResponse response = resource.uri(uri).type(APPLICATION_PORT_JSON)
                .post(ClientResponse.class, port);
        assertEquals(status, response.getStatus());
        return response.getLocation();
    }

    public DtoLogicalRouterPort getLogicalRouterPort(URI uri) {
        return getLogicalRouterPort(uri, OK.getStatusCode());
    }

    public DtoLogicalRouterPort getLogicalRouterPort(URI uri, int status) {
        ClientResponse response = resource.uri(uri).type(APPLICATION_PORT_JSON)
                .get(ClientResponse.class);
        assertEquals(status, response.getStatus());
        if (status == OK.getStatusCode()) {
            return response.getEntity(DtoLogicalRouterPort.class);
        } else {
            return null;
        }
    }

    public URI createMaterializedBridgePort(URI uri, DtoBridgePort port) {
        return createMaterializedBridgePort(uri, port, CREATED.getStatusCode());
    }

    public URI createMaterializedBridgePort(URI uri, DtoBridgePort port,
            int status) {
        ClientResponse response = resource.uri(uri).type(APPLICATION_PORT_JSON)
                .post(ClientResponse.class, port);
        assertEquals(status, response.getStatus());
        return response.getLocation();
    }

    public DtoBridgePort getMaterializedBridgePort(URI uri) {
        return getMaterializedBridgePort(uri, OK.getStatusCode());
    }

    public DtoBridgePort getMaterializedBridgePort(URI uri, int status) {
        ClientResponse response = resource.uri(uri).type(APPLICATION_PORT_JSON)
                .get(ClientResponse.class);
        assertEquals(status, response.getStatus());
        if (status == OK.getStatusCode()) {
            return response.getEntity(DtoBridgePort.class);
        } else {
            return null;
        }
    }

    public URI createLogicalBridgePort(URI uri, DtoLogicalBridgePort port) {
        return createLogicalBridgePort(uri, port, CREATED.getStatusCode());
    }

    public URI createLogicalBridgePort(URI uri, DtoLogicalBridgePort port,
            int status) {
        ClientResponse response = resource.uri(uri).type(APPLICATION_PORT_JSON)
                .post(ClientResponse.class, port);
        assertEquals(status, response.getStatus());
        return response.getLocation();
    }

    public DtoLogicalBridgePort getLogicalBridgePort(URI uri) {
        return getLogicalBridgePort(uri, OK.getStatusCode());
    }

    public DtoLogicalBridgePort getLogicalBridgePort(URI uri, int status) {
        ClientResponse response = resource.uri(uri).type(APPLICATION_PORT_JSON)
                .get(ClientResponse.class);
        assertEquals(status, response.getStatus());
        if (status == OK.getStatusCode()) {
            return response.getEntity(DtoLogicalBridgePort.class);
        } else {
            return null;
        }
    }

    public void deletePort(URI uri) {
        deletePort(uri, NO_CONTENT.getStatusCode());
    }

    public void deletePort(URI uri, int status) {
        ClientResponse response = resource.uri(uri).type(APPLICATION_PORT_JSON)
                .delete(ClientResponse.class);
        assertEquals(status, response.getStatus());
    }

    public List<DtoRouterPort> getRouterPorts(URI uri) {
        return getRouterPorts(uri, OK.getStatusCode());
    }

    public List<DtoRouterPort> getRouterPorts(URI uri, int status) {
        GenericType<List<DtoRouterPort>> genericType = new GenericType<List<DtoRouterPort>>() {
        };
        ClientResponse response = resource.uri(uri)
                .type(APPLICATION_PORT_COLLECTION_JSON)
                .get(ClientResponse.class);
        assertEquals(status, response.getStatus());
        if (status == OK.getStatusCode()) {
            return response.getEntity(genericType);
        } else {
            return null;
        }
    }

    public List<DtoBridgePort> getBridgePorts(URI uri) {
        return getBridgePorts(uri, OK.getStatusCode());
    }

    public List<DtoBridgePort> getBridgePorts(URI uri, int status) {
        GenericType<List<DtoBridgePort>> genericType = new GenericType<List<DtoBridgePort>>() {
        };
        ClientResponse response = resource.uri(uri)
                .type(APPLICATION_PORT_COLLECTION_JSON)
                .get(ClientResponse.class);
        assertEquals(status, response.getStatus());
        if (status == OK.getStatusCode()) {
            return response.getEntity(genericType);
        } else {
            return null;
        }
    }

    public List<DtoPort> getPeerPorts(URI uri) {
        return getPeerPorts(uri, OK.getStatusCode());
    }

    public List<DtoPort> getPeerPorts(URI uri, int status) {
        GenericType<List<DtoPort>> genericType = new GenericType<List<DtoPort>>() {
        };
        ClientResponse response = resource.uri(uri)
                .type(APPLICATION_PORT_COLLECTION_JSON)
                .get(ClientResponse.class);
        assertEquals(status, response.getStatus());
        if (status == OK.getStatusCode()) {
            return response.getEntity(genericType);
        } else {
            return null;
        }
    }

    public void linkPorts(URI uri, UUID peerId) {
        linkPorts(uri, peerId, NO_CONTENT.getStatusCode());
    }

    public void linkPorts(URI uri, UUID peerId, int status) {
        ClientResponse response = resource.uri(uri).type(APPLICATION_PORT_JSON)
                .post(ClientResponse.class, "{\"peerId\": \"" + peerId + "\"}");
        assertEquals(status, response.getStatus());
    }

    public void unlinkPorts(URI uri) {
        unlinkPorts(uri, NO_CONTENT.getStatusCode());
    }

    public void unlinkPorts(URI uri, int status) {
        ClientResponse response = resource.uri(uri).type(APPLICATION_PORT_JSON)
                .post(ClientResponse.class);
        assertEquals(status, response.getStatus());
    }

    public void verifyLink(DtoLogicalPort port, UUID peerPortId) {
        assertEquals(peerPortId, port.getPeerId());
        assertNotNull(port.getPeer());
        assertNotNull(port.getLink());
        assertNotNull(port.getUnlink());
    }

    public void verifyNoLink(DtoLogicalPort port) {
        assertNull(port.getPeerId());
        assertNull(port.getPeer());
        assertNotNull(port.getLink());
        assertNotNull(port.getUnlink());
    }
}
