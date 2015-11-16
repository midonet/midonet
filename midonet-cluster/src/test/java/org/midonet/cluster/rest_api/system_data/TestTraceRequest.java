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
package org.midonet.cluster.rest_api.system_data;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.ClientResponse.Status;
import com.sun.jersey.api.client.GenericType;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

import org.apache.zookeeper.KeeperException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoRouter;
import org.midonet.client.dto.DtoRouterPort;
import org.midonet.cluster.data.TraceRequest.DeviceType;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.rest_api.models.Condition;
import org.midonet.cluster.rest_api.models.TraceRequest;
import org.midonet.cluster.rest_api.rest_api.DtoWebResource;
import org.midonet.cluster.rest_api.rest_api.FuncTest;
import org.midonet.cluster.rest_api.rest_api.Topology;
import org.midonet.cluster.backend.zookeeper.StateAccessException;

import static java.lang.System.currentTimeMillis;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.midonet.cluster.rest_api.auth.AuthFilter.HEADER_X_AUTH_TOKEN;
import static org.midonet.cluster.rest_api.conversion.TraceRequestDataConverter.toData;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TRACE_REQUEST_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TRACE_REQUEST_JSON;

public class TestTraceRequest extends JerseyTest {

    private static final String ADMIN0 = "admin0";
    private static final String TENANT0 = "no_auth_tenant_id";
    private static final String ROUTER0 = "router0";
    private static final String BRIDGE0 = "bridge0";
    private static final String PORT0 = "port0";

    private Topology topology;
    private WebResource traceResource;

    public TestTraceRequest() {
        super(FuncTest.getBuilder().build());
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

        traceResource = resource().path(ResourceUris.TRACE_REQUESTS);
    }

    private UUID toUUID(URI uri) {
        return UUID.fromString(traceResource.getURI()
                .relativize(uri).toString());
    }

    public TraceRequest traceRequest(UUID id, String name, DeviceType devType,
                        UUID deviceId, Condition condition) {
        TraceRequest tr = new TraceRequest(id, name, devType, deviceId,
                                           condition, currentTimeMillis(),
                                           Long.MAX_VALUE, false);
        tr.setBaseUri(getBaseURI());
        return tr;
    }

    /**
     * Test basic operations on resources
     */
    @Test(timeout=60000)
    public void testCRD() throws StateAccessException {

        TraceRequest request = traceRequest(UUID.randomUUID(), "foobar",
                DeviceType.PORT, topology.getRouterPort(PORT0).getId(),
                new Condition());
        ClientResponse response = traceResource
            .type(APPLICATION_TRACE_REQUEST_JSON())
            .post(ClientResponse.class, request);
        assertThat("Create should have succeeded", response.getStatus(),
                   equalTo(Status.CREATED.getStatusCode()));
        request.id = toUUID(response.getLocation());
        request.setBaseUri(resource().getURI());

        List<TraceRequest> traces = traceResource
            .accept(APPLICATION_TRACE_REQUEST_COLLECTION_JSON())
            .get(new GenericType<List<TraceRequest>>() {});
        assertThat("The trace request is listed", traces.size(), equalTo(1));

        TraceRequest readRequest = traceResource.uri(request.getUri())
            .accept(APPLICATION_TRACE_REQUEST_JSON())
            .get(TraceRequest.class);
        assertThat("The object is different", request != readRequest);

        Assert.assertEquals(toData(request), toData(readRequest));
        assertThat("The content is the same",
                   toData(request).equals(toData(readRequest)));

        // create a second
        TraceRequest request2 = traceRequest(UUID.randomUUID(), "foobar2",
                DeviceType.BRIDGE, topology.getBridge(BRIDGE0).getId(),
                new Condition());
        ClientResponse response2 = traceResource
            .type(APPLICATION_TRACE_REQUEST_JSON())
            .post(ClientResponse.class, request2);
        assertThat("Create should have succeeded", response2.getStatus(),
                   equalTo(Status.CREATED.getStatusCode()));
        request2.id = toUUID(response.getLocation());

        traces = traceResource
            .accept(APPLICATION_TRACE_REQUEST_COLLECTION_JSON())
            .get(new GenericType<List<TraceRequest>>() {});
        assertThat("There should be two now", traces.size(), equalTo(2));

        // delete one
        ClientResponse response3 = traceResource.uri(request.getUri())
            .delete(ClientResponse.class);
        assertThat("Delete should have succeeded", response3.getStatus(),
                   equalTo(Status.NO_CONTENT.getStatusCode()));

        // should not be able to read it now
        ClientResponse response4 = traceResource.uri(request.getUri())
            .accept(APPLICATION_TRACE_REQUEST_JSON())
            .get(ClientResponse.class);
        assertThat("Should be gone", response4.getStatus(),
                   equalTo(Status.NOT_FOUND.getStatusCode()));

        traces = traceResource
            .accept(APPLICATION_TRACE_REQUEST_COLLECTION_JSON())
            .get(new GenericType<List<TraceRequest>>() {});
        assertThat("There should be one now", traces.size(), equalTo(1));
    }

    /**
     * Test that creating the same resource twice will fail
     */
    @Test(timeout=60000)
    public void testDoubleCreate() throws StateAccessException {
        TraceRequest request = traceRequest(UUID.randomUUID(), "foobar",
                DeviceType.PORT, topology.getRouterPort(PORT0).getId(),
                new Condition());
        ClientResponse response = traceResource
            .type(APPLICATION_TRACE_REQUEST_JSON())
            .post(ClientResponse.class, request);
        assertThat("Create should have succeeded", response.getStatus(),
                   equalTo(Status.CREATED.getStatusCode()));

        response = traceResource
            .type(APPLICATION_TRACE_REQUEST_JSON())
            .post(ClientResponse.class, request);
        assertThat("Create should not have succeeded", response.getStatus(),
                   equalTo(Status.CONFLICT.getStatusCode()));
        List<TraceRequest> traces = traceResource
            .accept(APPLICATION_TRACE_REQUEST_COLLECTION_JSON())
            .get(new GenericType<List<TraceRequest>>() {});
        assertThat("The trace request is listed", traces.size(), equalTo(1));
    }

    /**
     * Test that tenants are limited in what they do.
     */
    @Test(timeout=60000)
    public void testTenantLimitations() throws StateAccessException {
        // create two traces, the tenant owns the port but not the bridge
        TraceRequest portTrace = traceRequest(UUID.randomUUID(), "foobar",
                DeviceType.PORT, topology.getRouterPort(PORT0).getId(),
                new Condition());
        TraceRequest bridgeTrace = traceRequest(UUID.randomUUID(),
                "foobar2", DeviceType.BRIDGE,
                topology.getBridge(BRIDGE0).getId(), new Condition());
        ClientResponse response = traceResource
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .type(APPLICATION_TRACE_REQUEST_JSON())
            .post(ClientResponse.class, portTrace);
        assertThat("Create should have succeeded", response.getStatus(),
                   equalTo(Status.CREATED.getStatusCode()));
        portTrace.id = toUUID(response.getLocation());
        portTrace.setBaseUri(resource().getURI());

        response = traceResource
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .type(APPLICATION_TRACE_REQUEST_JSON())
            .post(ClientResponse.class, bridgeTrace);
        assertThat("Create should have succeeded", response.getStatus(),
                   equalTo(Status.CREATED.getStatusCode()));
        bridgeTrace.id = toUUID(response.getLocation());
        bridgeTrace.setBaseUri(resource().getURI());

        // tenant should only see the port trace
        List<TraceRequest> traces = traceResource
            .queryParam("tenant_id", TENANT0)
            .header(HEADER_X_AUTH_TOKEN, TENANT0)
            .accept(APPLICATION_TRACE_REQUEST_COLLECTION_JSON())
            .get(new GenericType<List<TraceRequest>>() {});
        assertThat("The trace request is listed", traces.size(), equalTo(1));
        assertThat("Should be the port trace",
                   traces.get(0).id, equalTo(portTrace.id));

        // admin should see all
        traces = traceResource
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .accept(APPLICATION_TRACE_REQUEST_COLLECTION_JSON())
            .get(new GenericType<List<TraceRequest>>() {});
        assertThat("Both trace requests are listed", traces.size(), equalTo(2));

        // tenant can delete port trace but not bridge trace
        response = traceResource.uri(portTrace.getUri())
            .queryParam("tenant_id", TENANT0)
            .header(HEADER_X_AUTH_TOKEN, TENANT0)
            .delete(ClientResponse.class);
        assertThat("Delete should have succeeded for portTrace",
                   response.getStatus(),
                   equalTo(Status.NO_CONTENT.getStatusCode()));

        // v2 api only has admin tenant
    }

    /**
     * Test that conditions are stored correctly.
     */
    @Test(timeout=60000)
    public void testConditionStorage() throws StateAccessException {
        Condition condition = new Condition();
        condition.nwDstAddress = "10.0.0.1";
        condition.nwSrcAddress = "10.0.0.2";

        TraceRequest portTrace = traceRequest(
                UUID.randomUUID(), "foobar",
                DeviceType.PORT, topology.getRouterPort(PORT0).getId(),
                condition);
        portTrace.setBaseUri(resource().getURI());

        ClientResponse response = traceResource
            .type(APPLICATION_TRACE_REQUEST_JSON())
            .post(ClientResponse.class, portTrace);
        assertThat("Create should have succeeded", response.getStatus(),
                   equalTo(Status.CREATED.getStatusCode()));
        portTrace.id = toUUID(response.getLocation());

        TraceRequest readRequest = traceResource.uri(portTrace.getUri())
            .accept(APPLICATION_TRACE_REQUEST_JSON())
            .get(TraceRequest.class);
        assertThat("The object is different", portTrace != readRequest);
        assertThat("The content is the same",
                   toData(portTrace).equals(toData(readRequest)));
        assertThat("Addresses are set correctly",
                   readRequest.condition.nwDstAddress.equals("10.0.0.1") &&
                   readRequest.condition.nwSrcAddress.equals("10.0.0.2"));
    }

    @Test(timeout=60000)
    public void testCreateWithNonExistantDevice() throws StateAccessException {
        TraceRequest portTrace = traceRequest(
                UUID.randomUUID(), "foobar",
                DeviceType.BRIDGE, UUID.randomUUID(), new Condition());
        ClientResponse response = traceResource
            .type(APPLICATION_TRACE_REQUEST_JSON())
            .post(ClientResponse.class, portTrace);
        assertThat("Create should have failed", response.getStatus(),
                   equalTo(Status.NOT_FOUND.getStatusCode()));
    }

    @Test(timeout=60000)
    public void testCreateWithWrongType() throws StateAccessException {
        TraceRequest portTrace = traceRequest(
                UUID.randomUUID(), "foobar",
                DeviceType.BRIDGE, topology.getRouterPort(PORT0).getId(),
                new Condition());
        ClientResponse response = traceResource
            .type(APPLICATION_TRACE_REQUEST_JSON())
            .post(ClientResponse.class, portTrace);
        assertThat("Create should have failed", response.getStatus(),
                   equalTo(Status.NOT_FOUND.getStatusCode()));
    }

    @Test(timeout=60000)
    public void testEnableDisable() throws StateAccessException {
        TraceRequest portTrace = traceRequest(UUID.randomUUID(), "foobar",
                DeviceType.PORT, topology.getRouterPort(PORT0).getId(),
                new Condition());

        ClientResponse response = traceResource
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .type(APPLICATION_TRACE_REQUEST_JSON())
            .post(ClientResponse.class, portTrace);
        assertThat("Create should have succeeded", response.getStatus(),
                   equalTo(Status.CREATED.getStatusCode()));
        portTrace.id = toUUID(response.getLocation());
        portTrace.setBaseUri(resource().getURI());

        TraceRequest readRequest = traceResource.uri(portTrace.getUri())
            .accept(APPLICATION_TRACE_REQUEST_JSON())
            .get(TraceRequest.class);
        assertThat("Trace hasn't been enabled",
                   readRequest.enabled, equalTo(false));

        portTrace.enabled = false;
        response = traceResource.uri(portTrace.getUri())
            .type(APPLICATION_TRACE_REQUEST_JSON())
            .put(ClientResponse.class, portTrace);
        assertThat("Got correct http response code", response.getStatus(),
                   equalTo(Status.NO_CONTENT.getStatusCode()));
        readRequest = traceResource.uri(portTrace.getUri())
            .accept(APPLICATION_TRACE_REQUEST_JSON())
            .get(TraceRequest.class);
        assertThat("Trace still hasn't been enabled",
                   readRequest.enabled, equalTo(false));

        portTrace.enabled = true;
        response = traceResource.uri(portTrace.getUri())
            .type(APPLICATION_TRACE_REQUEST_JSON())
            .put(ClientResponse.class, portTrace);
        assertThat("Got correct http response code", response.getStatus(),
                   equalTo(Status.NO_CONTENT.getStatusCode()));

        readRequest = traceResource.uri(portTrace.getUri())
            .accept(APPLICATION_TRACE_REQUEST_JSON())
            .get(TraceRequest.class);
        assertThat("Trace has been enabled",
                   readRequest.enabled, equalTo(true));
    }

    @Test(timeout=60000)
    public void testEnabledOnCreation() throws StateAccessException {
        TraceRequest bridgeTrace = traceRequest(UUID.randomUUID(), "foobar",
                DeviceType.BRIDGE, topology.getBridge(BRIDGE0).getId(),
                new Condition());
        bridgeTrace.creationTimestampMs = currentTimeMillis();
        bridgeTrace.limit = Long.MAX_VALUE;
        bridgeTrace.enabled = true;

        ClientResponse response = traceResource
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .type(APPLICATION_TRACE_REQUEST_JSON())
            .post(ClientResponse.class, bridgeTrace);
        assertThat("Create should have succeeded", response.getStatus(),
                   equalTo(Status.CREATED.getStatusCode()));
        bridgeTrace.id = toUUID(response.getLocation());
        bridgeTrace.setBaseUri(resource().getURI());

        TraceRequest readRequest = traceResource.uri(bridgeTrace.getUri())
            .accept(APPLICATION_TRACE_REQUEST_JSON())
            .get(TraceRequest.class);
        assertThat("Trace has been enabled",
                   readRequest.enabled, equalTo(true));
    }

    @Test(timeout=60000)
    public void testConflictingPut() throws StateAccessException {

        TraceRequest bridgeTrace = traceRequest(UUID.randomUUID(), "foobar",
                DeviceType.BRIDGE, topology.getBridge(BRIDGE0).getId(),
                new Condition());
        bridgeTrace.creationTimestampMs = currentTimeMillis();
        bridgeTrace.limit = Long.MAX_VALUE;
        bridgeTrace.enabled = true;

        ClientResponse response = traceResource
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .type(APPLICATION_TRACE_REQUEST_JSON())
            .post(ClientResponse.class, bridgeTrace);
        assertThat("Create should have succeeded", response.getStatus(),
                   equalTo(Status.CREATED.getStatusCode()));
        bridgeTrace.id = toUUID(response.getLocation());
        bridgeTrace.setBaseUri(resource().getURI());

        bridgeTrace.enabled = true;
        bridgeTrace.deviceId = UUID.randomUUID();
        response = traceResource.uri(bridgeTrace.getUri())
            .header(HEADER_X_AUTH_TOKEN, ADMIN0)
            .type(APPLICATION_TRACE_REQUEST_JSON())
            .put(ClientResponse.class, bridgeTrace);
        assertThat("Got correct http response code", response.getStatus(),
                   equalTo(Status.NOT_FOUND.getStatusCode()));
    }
}
