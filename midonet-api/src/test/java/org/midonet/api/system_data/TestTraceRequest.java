/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.api.system_data;

import java.net.URI;
import java.util.UUID;
import java.util.List;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.ClientResponse.Status;
import com.sun.jersey.api.client.GenericType;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.ResourceUriBuilder;
import static org.midonet.api.auth.AuthFilter.HEADER_X_AUTH_TOKEN;
import org.midonet.api.auth.MockAuthConfig;
import org.midonet.api.filter.Condition;
import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.Topology;

import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoRouter;
import org.midonet.client.dto.DtoRouterPort;
import org.midonet.cluster.data.TraceRequest.DeviceType;
import org.midonet.midolman.state.StateAccessException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class TestTraceRequest extends JerseyTest {
    private final static Logger log
        = LoggerFactory.getLogger(TestTraceRequest.class);

    private static final String ADMIN0 = "admin0";
    private static final String TENANT0 = "no_auth_tenant_id";
    private static final String ROUTER0 = "router0";
    private static final String BRIDGE0 = "bridge0";
    private static final String PORT0 = "port0";

    private Topology topology;
    private WebResource traceResource;

    public TestTraceRequest() {
        super(FuncTest.getBuilder()
              .contextParam(FuncTest.getConfigKey(MockAuthConfig.GROUP_NAME,
                                    MockAuthConfig.ADMIN_TOKEN_KEY),
                            ADMIN0)
              .contextParam(FuncTest.getConfigKey(MockAuthConfig.GROUP_NAME,
                                    MockAuthConfig.TENANT_ADMIN_TOKEN_KEY),
                            TENANT0)
              .build());
    }

    @Before
    public void setUp() throws InterruptedException,
                               KeeperException,
                               StateAccessException {
        DtoWebResource dtoWebResource = new DtoWebResource(resource());

        Topology.Builder builder = new Topology.Builder(dtoWebResource);

        DtoRouter router = new DtoRouter();
        router.setName(ROUTER0);
        router.setTenantId(TENANT0);
        builder.create(ROUTER0, router);

        DtoRouterPort port = new DtoRouterPort();
        port.setNetworkAddress("10.0.0.0");
        port.setNetworkLength(24);
        port.setPortAddress("10.0.0.1");
        builder.create(ROUTER0, PORT0, port);

        DtoBridge bridge = new DtoBridge();
        bridge.setName(BRIDGE0);
        bridge.setTenantId("dummyTenant");
        builder.create(BRIDGE0, bridge);

        topology = builder.build();

        traceResource = resource().path(ResourceUriBuilder.TRACE_REQUESTS);
    }

    private UUID toUUID(URI uri) {
        return UUID.fromString(traceResource.getURI()
                .relativize(uri).toString());
    }

    /**
     * Test basic operations on resources
     */
    @Test(timeout=60000)
    public void testCRD() throws StateAccessException {

        TraceRequest request = new TraceRequest(UUID.randomUUID(), "foobar",
                DeviceType.PORT, topology.getRouterPort(PORT0).getId(),
                new Condition());
        ClientResponse response = traceResource.post(ClientResponse.class,
                                                     request);
        assertThat("Create should have succeeded",
                response.getClientResponseStatus(), equalTo(Status.CREATED));
        request.setId(toUUID(response.getLocation()));
        request.setBaseUri(resource().getURI());

        List<TraceRequest> traces = traceResource
            .get(new GenericType<List<TraceRequest>>() {});
        assertThat("The trace request is listed", traces.size(), equalTo(1));

        TraceRequest readRequest = traceResource.uri(request.getUri())
            .get(TraceRequest.class);
        assertThat("The object is different", request != readRequest);
        assertThat("The content is the same",
                request.toData().equals(readRequest.toData()));

        // create a second
        TraceRequest request2 = new TraceRequest(UUID.randomUUID(), "foobar2",
                DeviceType.BRIDGE, topology.getBridge(BRIDGE0).getId(),
                new Condition());
        ClientResponse response2 = traceResource.post(ClientResponse.class,
                                                      request2);
        assertThat("Create should have succeeded",
                response2.getClientResponseStatus(), equalTo(Status.CREATED));
        request2.setId(toUUID(response.getLocation()));

        traces = traceResource.get(new GenericType<List<TraceRequest>>() {});
        assertThat("There should be two now", traces.size(), equalTo(2));

        // delete one
        ClientResponse response3 = traceResource.uri(request.getUri())
            .delete(ClientResponse.class);
        assertThat("Delete should have succeeded", response3.getClientResponseStatus(),
                equalTo(Status.NO_CONTENT));

        // should not be able to read it now
        ClientResponse response4 = traceResource.uri(request.getUri())
            .get(ClientResponse.class);
        assertThat("Should be gone", response4.getClientResponseStatus(),
                   equalTo(Status.NOT_FOUND));

        traces = traceResource.get(new GenericType<List<TraceRequest>>() {});
        assertThat("There should be one now", traces.size(), equalTo(1));
    }

    /**
     * Test that creating the same resource twice will fail
     */
    @Test(timeout=60000)
    public void testDoubleCreate() throws StateAccessException {
        TraceRequest request = new TraceRequest(UUID.randomUUID(), "foobar",
                DeviceType.PORT, topology.getRouterPort(PORT0).getId(),
                new Condition());
        ClientResponse response = traceResource.post(ClientResponse.class,
                                                     request);
        assertThat("Create should have succeeded",
                response.getClientResponseStatus(), equalTo(Status.CREATED));

        response = traceResource.post(ClientResponse.class,
                                                     request);
        assertThat("Create should have not have succeeded",
                   response.getClientResponseStatus(),
                   equalTo(Status.INTERNAL_SERVER_ERROR));

        List<TraceRequest> traces = traceResource
            .get(new GenericType<List<TraceRequest>>() {});
        assertThat("The trace request is listed", traces.size(), equalTo(1));
    }

    /**
     * Test that tenants are limited in what they do.
     */
    @Test(timeout=60000)
    public void testTenantLimitations() throws StateAccessException {
        // create two traces, the tenant owns the port but not the bridge
        TraceRequest portTrace = new TraceRequest(UUID.randomUUID(), "foobar",
                DeviceType.PORT, topology.getRouterPort(PORT0).getId(),
                new Condition());
        TraceRequest bridgeTrace = new TraceRequest(UUID.randomUUID(),
                "foobar2", DeviceType.BRIDGE,
                topology.getBridge(BRIDGE0).getId(), new Condition());
        ClientResponse response = traceResource
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .post(ClientResponse.class, portTrace);
        assertThat("Create should have succeeded",
                response.getClientResponseStatus(), equalTo(Status.CREATED));
        portTrace.setId(toUUID(response.getLocation()));
        portTrace.setBaseUri(resource().getURI());

        response = traceResource
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .post(ClientResponse.class, bridgeTrace);
        assertThat("Create should have succeeded",
                response.getClientResponseStatus(), equalTo(Status.CREATED));
        bridgeTrace.setId(toUUID(response.getLocation()));
        bridgeTrace.setBaseUri(resource().getURI());

        // tenant should only see the port trace
        List<TraceRequest> traces = traceResource
            .header(HEADER_X_AUTH_TOKEN, TENANT0)
            .get(new GenericType<List<TraceRequest>>() {});
        assertThat("The trace request is listed", traces.size(), equalTo(1));
        assertThat("Should be the port trace",
                   traces.get(0).getId(), equalTo(portTrace.getId()));

        // admin should see all
        traces = traceResource
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .get(new GenericType<List<TraceRequest>>() {});
        assertThat("Both trace requests are listed", traces.size(), equalTo(2));

        // tenant can delete port trace but not bridge trace
        response = traceResource.uri(portTrace.getUri())
            .header(HEADER_X_AUTH_TOKEN, TENANT0)
            .delete(ClientResponse.class);
        assertThat("Delete should have succeeded for portTrace",
                response.getClientResponseStatus(),
                equalTo(Status.NO_CONTENT));

        response = traceResource.uri(bridgeTrace.getUri())
            .header(HEADER_X_AUTH_TOKEN, TENANT0)
            .delete(ClientResponse.class);
        assertThat("Delete should have failed",
                response.getClientResponseStatus(),
                equalTo(Status.FORBIDDEN));

        traces = traceResource
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .get(new GenericType<List<TraceRequest>>() {});
        assertThat("Only one race requests is listed", traces.size(), equalTo(1));

        // tenant can create trace on router, but not bridge
        TraceRequest bridgeTrace2 = new TraceRequest(
                UUID.randomUUID(), "foobar2",
                DeviceType.BRIDGE, topology.getBridge(BRIDGE0).getId(),
                new Condition());
        TraceRequest portTrace2 = new TraceRequest(
                UUID.randomUUID(), "foobar3",
                DeviceType.PORT, topology.getRouterPort(PORT0).getId(),
                new Condition());

        response = traceResource
            .header(HEADER_X_AUTH_TOKEN, TENANT0)
            .post(ClientResponse.class, portTrace2);
        assertThat("Create should have succeeded",
                response.getClientResponseStatus(), equalTo(Status.CREATED));

        response = traceResource
            .header(HEADER_X_AUTH_TOKEN, TENANT0)
            .post(ClientResponse.class, bridgeTrace2);
        assertThat("Create should have failed",
                response.getClientResponseStatus(), equalTo(Status.FORBIDDEN));

        traces = traceResource
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .get(new GenericType<List<TraceRequest>>() {});
        assertThat("Both trace requests are listed", traces.size(), equalTo(2));
    }

    /**
     * Test that conditions are stored correctly.
     */
    @Test(timeout=60000)
    public void testConditionStorage() throws StateAccessException {
        Condition condition = new Condition();
        condition.setNwDstAddress("10.0.0.1");
        condition.setNwSrcAddress("10.0.0.2");

        TraceRequest portTrace = new TraceRequest(
                UUID.randomUUID(), "foobar",
                DeviceType.PORT, topology.getRouterPort(PORT0).getId(),
                condition);
        ClientResponse response = traceResource
            .post(ClientResponse.class, portTrace);
        assertThat("Create should have succeeded",
                   response.getClientResponseStatus(),
                   equalTo(Status.CREATED));
        portTrace.setId(toUUID(response.getLocation()));
        portTrace.setBaseUri(resource().getURI());

        TraceRequest readRequest = traceResource.uri(portTrace.getUri())
            .get(TraceRequest.class);
        assertThat("The object is different", portTrace != readRequest);
        assertThat("The content is the same",
                   portTrace.toData().equals(readRequest.toData()));
        assertThat("Addresses are set correctly",
                readRequest.getCondition().getNwDstAddress().equals("10.0.0.1")
                && readRequest.getCondition()
                   .getNwSrcAddress().equals("10.0.0.2"));
    }

    @Test(timeout=60000)
    public void testCreateWithNonExistantDevice() throws StateAccessException {
        TraceRequest portTrace = new TraceRequest(
                UUID.randomUUID(), "foobar",
                DeviceType.BRIDGE, UUID.randomUUID(), new Condition());
        ClientResponse response = traceResource
            .post(ClientResponse.class, portTrace);
        assertThat("Create should have failed",
                   response.getClientResponseStatus(),
                   equalTo(Status.INTERNAL_SERVER_ERROR));
    }

    @Test(timeout=60000)
    public void testCreateWithWrongType() throws StateAccessException {
        TraceRequest portTrace = new TraceRequest(
                UUID.randomUUID(), "foobar",
                DeviceType.BRIDGE, topology.getRouterPort(PORT0).getId(),
                new Condition());
        ClientResponse response = traceResource
            .post(ClientResponse.class, portTrace);
        assertThat("Create should have failed",
                   response.getClientResponseStatus(),
                   equalTo(Status.INTERNAL_SERVER_ERROR));
    }

    @Test(timeout=60000)
    public void testEnableDisable() throws StateAccessException {
        TraceRequest portTrace = new TraceRequest(UUID.randomUUID(), "foobar",
                DeviceType.PORT, topology.getRouterPort(PORT0).getId(),
                new Condition());

        ClientResponse response = traceResource
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .post(ClientResponse.class, portTrace);
        assertThat("Create should have succeeded",
                response.getClientResponseStatus(), equalTo(Status.CREATED));
        portTrace.setId(toUUID(response.getLocation()));
        portTrace.setBaseUri(resource().getURI());

        TraceRequest readRequest = traceResource.uri(portTrace.getUri())
            .get(TraceRequest.class);
        assertThat("Trace hasn't been enabled",
                   readRequest.getEnabled(), equalTo(false));

        portTrace.setEnabled(false);
        response = traceResource.uri(portTrace.getUri())
            .put(ClientResponse.class, portTrace);
        assertThat("Got correct http response code",
                   response.getClientResponseStatus(),
                   equalTo(Status.NO_CONTENT));
        readRequest = traceResource.uri(portTrace.getUri())
            .get(TraceRequest.class);
        assertThat("Trace still hasn't been enabled",
                   readRequest.getEnabled(), equalTo(false));

        portTrace.setEnabled(true);
        response = traceResource.uri(portTrace.getUri())
            .put(ClientResponse.class, portTrace);
        assertThat("Got correct http response code",
                   response.getClientResponseStatus(),
                   equalTo(Status.NO_CONTENT));

        readRequest = traceResource.uri(portTrace.getUri())
            .get(TraceRequest.class);
        assertThat("Trace has been enabled",
                   readRequest.getEnabled(), equalTo(true));
    }

    @Test(timeout=60000)
    public void testEnableDisablePermissions() throws StateAccessException {
        TraceRequest bridgeTrace = new TraceRequest(UUID.randomUUID(), "foobar",
                DeviceType.BRIDGE, topology.getBridge(BRIDGE0).getId(),
                new Condition());

        ClientResponse response = traceResource
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .post(ClientResponse.class, bridgeTrace);
        assertThat("Create should have succeeded",
                response.getClientResponseStatus(), equalTo(Status.CREATED));
        bridgeTrace.setId(toUUID(response.getLocation()));
        bridgeTrace.setBaseUri(resource().getURI());

        TraceRequest readRequest = traceResource.uri(bridgeTrace.getUri())
            .get(TraceRequest.class);
        assertThat("Trace hasn't been enabled",
                   readRequest.getEnabled(), equalTo(false));

        bridgeTrace.setEnabled(false);
        response = traceResource.uri(bridgeTrace.getUri())
            .header(HEADER_X_AUTH_TOKEN, TENANT0)
            .put(ClientResponse.class, bridgeTrace);
        assertThat("Request was forbidden", response.getClientResponseStatus(),
                   equalTo(Status.FORBIDDEN));

        readRequest = traceResource.uri(bridgeTrace.getUri())
            .get(TraceRequest.class);
        assertThat("Trace hasn't been enabled",
                   readRequest.getEnabled(), equalTo(false));

        bridgeTrace.setEnabled(true);
        response = traceResource.uri(bridgeTrace.getUri())
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .put(ClientResponse.class, bridgeTrace);
        assertThat("Got correct http response code",
                   response.getClientResponseStatus(),
                   equalTo(Status.NO_CONTENT));

        readRequest = traceResource.uri(bridgeTrace.getUri())
            .get(TraceRequest.class);
        assertThat("Trace has been enabled",
                   readRequest.getEnabled(), equalTo(true));
    }

    @Test(timeout=60000)
    public void testEnabledOnCreation() throws StateAccessException {
        TraceRequest bridgeTrace = new TraceRequest(UUID.randomUUID(), "foobar",
                DeviceType.BRIDGE, topology.getBridge(BRIDGE0).getId(),
                new Condition(), true);

        ClientResponse response = traceResource
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .post(ClientResponse.class, bridgeTrace);
        assertThat("Create should have succeeded",
                response.getClientResponseStatus(), equalTo(Status.CREATED));
        bridgeTrace.setId(toUUID(response.getLocation()));
        bridgeTrace.setBaseUri(resource().getURI());

        TraceRequest readRequest = traceResource.uri(bridgeTrace.getUri())
            .get(TraceRequest.class);
        assertThat("Trace has been enabled",
                   readRequest.getEnabled(), equalTo(true));
    }

    @Test(timeout=60000)
    public void testConflictingPut() throws StateAccessException {
        TraceRequest bridgeTrace = new TraceRequest(UUID.randomUUID(), "foobar",
                DeviceType.BRIDGE, topology.getBridge(BRIDGE0).getId(),
                new Condition(), true);

        ClientResponse response = traceResource
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .post(ClientResponse.class, bridgeTrace);
        assertThat("Create should have succeeded",
                response.getClientResponseStatus(), equalTo(Status.CREATED));
        bridgeTrace.setId(toUUID(response.getLocation()));
        bridgeTrace.setBaseUri(resource().getURI());

        bridgeTrace.setEnabled(true);
        bridgeTrace.setDeviceId(UUID.randomUUID());
        response = traceResource.uri(bridgeTrace.getUri())
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .put(ClientResponse.class, bridgeTrace);
        assertThat("Got correct http response code",
                   response.getClientResponseStatus(),
                   equalTo(Status.CONFLICT));
    }
}
