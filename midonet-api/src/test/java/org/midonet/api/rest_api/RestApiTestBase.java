/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.rest_api;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.UUID;
import javax.ws.rs.core.UriBuilder;

import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.After;
import org.junit.Before;
import org.midonet.api.validation.MessageProperty;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoPort;
import static junit.framework.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.midonet.client.VendorMediaType.APPLICATION_BRIDGE_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_PORT_V2_JSON;

public abstract class RestApiTestBase extends JerseyTest {

    protected DtoWebResource dtoResource;
    protected Topology topology;
    protected DtoApplication app;

    public RestApiTestBase(AppDescriptor desc) {
        super(desc);
    }

    @Before
    public void setUp() throws Exception {
        super.setUp();
        dtoResource = new DtoWebResource(resource());
        Topology.Builder builder = new Topology.Builder(dtoResource);

        // Allow subclasses to add to topology before building.
        extendTopology(builder);

        topology = builder.build();
        app = topology.getApplication();
    }

    @After
    public void resetDirectory() throws Exception {
        StaticMockDirectory.clearDirectoryInstance();
    }

    /**
     * Can be overridden to add objects to the topology before building it.
     */
    protected void extendTopology(Topology.Builder builder) {
    }

    protected void assertErrorMatches(
            DtoError actual, String expectedTemplateCode, Object... args) {
        String expectedMsg = MessageProperty.getMessage(expectedTemplateCode, args);
        String actualMsg = (actual.getViolations().isEmpty()) ?
                actual.getMessage() :
                actual.getViolations().get(0).get("message");
        assertEquals(expectedMsg, actualMsg);
    }

    protected void assertErrorMatchesPropMsg(
            DtoError actual, String expectedProperty, String expectedMessage) {
        // May need to relax this later.
        assertEquals(1, actual.getViolations().size());
        Map<String, String> violation = actual.getViolations().get(0);
        assertEquals(expectedProperty, violation.get("property"));
        assertEquals(expectedMessage, violation.get("message"));
    }

    protected URI addIdToUri(URI base, UUID id) throws URISyntaxException {
        return new URI(base.toString() + "/" + id.toString());
    }

    protected DtoBridge postBridge(String bridgeName) {
        DtoBridge bridge = new DtoBridge();
        bridge.setName(bridgeName);
        bridge.setTenantId("tenant1");
        bridge = dtoResource.postAndVerifyCreated(
                topology.getApplication().getBridges(),
                APPLICATION_BRIDGE_JSON, bridge, DtoBridge.class);
        assertNotNull(bridge.getId());
        assertNotNull(bridge.getUri());
        return bridge;
    }

    protected DtoBridge getBridge(UUID id) {
        URI uri = UriBuilder.fromPath(app.getBridgeTemplate()).build(id);
        return dtoResource.getAndVerifyOk(
                uri, APPLICATION_BRIDGE_JSON, DtoBridge.class);
    }

    protected DtoPort getPort(UUID id) {
        URI uri = UriBuilder.fromPath(app.getPortTemplate()).build(id);
        return dtoResource.getAndVerifyOk(
            uri, APPLICATION_PORT_V2_JSON, DtoPort.class);
    }

}
