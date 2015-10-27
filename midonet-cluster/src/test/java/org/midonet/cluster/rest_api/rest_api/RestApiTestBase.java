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
package org.midonet.cluster.rest_api.rest_api;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.JerseyTest;

import org.junit.Before;

import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoBridgePort;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoPort;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.*;

public abstract class RestApiTestBase extends JerseyTest {

    protected DtoWebResource dtoResource;
    protected Topology topology;
    protected DtoApplication app;

    public RestApiTestBase(AppDescriptor desc) {
        super(desc);
    }

    @Before @Override
    public void setUp() throws Exception {
        super.setUp();
        dtoResource = new DtoWebResource(resource());
        Topology.Builder builder = new Topology.Builder(dtoResource);

        // Allow subclasses to add to topology before building.
        extendTopology(builder);

        topology = builder.build();
        app = topology.getApplication();
    }

    /**
     * Can be overridden to add objects to the topology before building it.
     */
    protected void extendTopology(Topology.Builder builder) {
    }

    protected void assertErrorMatches(
            DtoError actual, String expectedTemplateCode, Object... args) {
        String expectedMsg = getMessage(expectedTemplateCode, args);
        assertErrorMatchesLiteral(actual, expectedMsg);
    }

    protected void assertErrorMatchesLiteral(DtoError actual,
                                             String expectedMsg) {
        String actualMsg = (actual.getViolations().isEmpty()) ?
                actual.getMessage() :
                actual.getViolations().get(0).get("message");
        assertEquals(expectedMsg, actualMsg);
    }

    protected void assertErrorMatchesPropMsg(
            DtoError actual, String expectedProperty,
            String expectedTemplateCode, Object... args) {
        // May need to relax this later.
        assertEquals(1, actual.getViolations().size());
        Map<String, String> violation = actual.getViolations().get(0);
        assertEquals(expectedProperty, violation.get("property"));
        assertEquals(getMessage(expectedTemplateCode, args),
                     violation.get("message"));
    }

    protected void assertValidationProperties(DtoError error,
                                              String ... properties) {
        Set<String> propertySet = new HashSet<>(Arrays.asList(properties));
        List<Map<String, String>> violations = error.getViolations();
        assertEquals(propertySet.size(), violations.size());
        for (Map<String, String> v : violations) {
            assertTrue(propertySet.contains(v.get("property")));
        }
    }

    protected URI addIdToUri(URI base, UUID id) throws URISyntaxException {
        return new URI(base.toString() + "/" + id.toString());
    }

    protected URI replaceInUri(URI uri, String oldStr, String newStr) {
        try {
            return new URI(uri.toString().replace(oldStr, newStr));
        } catch (URISyntaxException ex) {
            // This is fine for a test method.
            throw new RuntimeException(ex);
        }
    }

    protected DtoBridge postBridge(String bridgeName) {
        DtoBridge bridge = new DtoBridge();
        bridge.setName(bridgeName);
        bridge.setTenantId("tenant1");
        bridge = dtoResource.postAndVerifyCreated(
                topology.getApplication().getBridges(),
                APPLICATION_BRIDGE_JSON_V4(), bridge, DtoBridge.class);
        assertNotNull(bridge.getId());
        assertNotNull(bridge.getUri());
        return bridge;
    }

    protected DtoBridge getBridge(UUID id) {
        URI uri = UriBuilder.fromPath(app.getBridgeTemplate()).build(id);
        return dtoResource.getAndVerifyOk(
                uri, APPLICATION_BRIDGE_JSON_V4(), DtoBridge.class);
    }

    protected DtoPort getPort(UUID id) {
        URI uri = UriBuilder.fromPath(app.getPortTemplate()).build(id);
        return dtoResource.getAndVerifyOk(
                uri, APPLICATION_PORT_V3_JSON(), DtoPort.class);
    }

    public DtoBridgePort postBridgePort(DtoBridgePort port, DtoBridge bridge) {
        return dtoResource.postAndVerifyCreated(bridge.getPorts(),
                APPLICATION_PORT_V3_JSON(), port, DtoBridgePort.class);
    }
}
