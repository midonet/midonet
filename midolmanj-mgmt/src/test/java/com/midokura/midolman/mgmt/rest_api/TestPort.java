/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import com.midokura.midolman.mgmt.data.dto.client.*;
import com.midokura.midolman.mgmt.data.zookeeper.StaticMockDirectory;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import javax.ws.rs.core.Response;
import java.util.*;

import static com.midokura.midolman.mgmt.http.VendorMediaType.APPLICATION_PORT_JSON;
import static org.junit.Assert.*;

@RunWith(Enclosed.class)
public class TestPort {

    public static DtoMaterializedRouterPort createMaterializedRouterPort(
            UUID id, UUID deviceId, String networkAddr, int networkLen,
            String portAddr, String localNetworkAddr, int localNetworkLen,
            UUID vifId, UUID inboundFilterId, UUID outboundFilterId) {
        DtoMaterializedRouterPort port = new DtoMaterializedRouterPort();
        port.setId(id);
        port.setDeviceId(deviceId);
        port.setNetworkAddress(networkAddr);
        port.setNetworkLength(networkLen);
        port.setPortAddress(portAddr);
        port.setLocalNetworkAddress(localNetworkAddr);
        port.setLocalNetworkLength(localNetworkLen);
        port.setVifId(vifId);
        port.setInboundFilterId(inboundFilterId);
        port.setOutboundFilterId(outboundFilterId);
        return port;
    }

    public static DtoLogicalRouterPort createLogicalRouterPort(UUID id,
            UUID deviceId, String networkAddr, int networkLen, String portAddr) {
        DtoLogicalRouterPort port = new DtoLogicalRouterPort();
        port.setId(id);
        port.setDeviceId(deviceId);
        port.setNetworkAddress(networkAddr);
        port.setNetworkLength(networkLen);
        port.setPortAddress(portAddr);
        return port;
    }

    @RunWith(Parameterized.class)
    public static class TestCreateRouterPortBadRequest extends JerseyTest {

        private final DtoRouterPort port;
        private final String property;
        private DtoWebResource dtoResource;
        private Topology topology;

        public TestCreateRouterPortBadRequest(DtoRouterPort port,
                String property) {
            super(FuncTest.appDesc);
            this.port = port;
            this.property = property;
        }

        @Before
        public void setUp() {
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a router
            DtoRouter r = new DtoRouter();
            r.setName("router1-name");
            r.setTenantId("tenant1-id");

            topology = new Topology.Builder(dtoResource)
                    .create("router1", r).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<Object[]>();

            // Bad network address
            DtoRouterPort badNetworkAddr = createLogicalRouterPort(null, null,
                    "badAddr", 24, "192.168.100.1");
            params.add(new Object[] { badNetworkAddr, "networkAddress" });

            // Bad port address
            DtoRouterPort badPortAddr = createLogicalRouterPort(null, null,
                    "10.0.0.0", 24, "badAddr");
            params.add(new Object[] { badPortAddr, "portAddress" });

            // Bad network len
            DtoRouterPort networkLenTooBig = createLogicalRouterPort(null,
                    null, "10.0.0.0", 33, "192.168.100.1");
            params.add(new Object[] { networkLenTooBig, "networkLength" });

            // Negative network len
            DtoRouterPort networkLenNegative = createLogicalRouterPort(null,
                    null, "10.0.0.0", -1, "192.168.100.1");
            params.add(new Object[] { networkLenNegative, "networkLength" });

            return params;
        }

        @Test
        public void testBadInputCreate() {

            DtoRouter router = topology.getRouter("router1");

            DtoError error = dtoResource.postAndVerifyBadRequest(
                    router.getPorts(), APPLICATION_PORT_JSON, port);
            List<Map<String, String>> violations = error.getViolations();
            assertEquals(1, violations.size());
            assertEquals(property, violations.get(0).get("property"));
        }
    }

    public static class TestBridgePortCrudSuccess extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;

        public TestBridgePortCrudSuccess() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() {
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a router
            DtoBridge b = new DtoBridge();
            b.setName("bridge1-name");
            b.setTenantId("tenant1-id");

            // Create a chain
            DtoRuleChain c1 = new DtoRuleChain();
            c1.setName("chain1-name");
            c1.setTenantId("tenant1-id");

            // Create another chain
            DtoRuleChain c2 = new DtoRuleChain();
            c2.setName("chain2-name");
            c1.setTenantId("tenant1-id");

            topology = new Topology.Builder(dtoResource)
                    .create("chain1", c1)
                    .create("chain2", c2)
                    .create("bridge1", b).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Test
        public void testCrudBridgePort() {

            // Get the bridge and chains
            DtoBridge b = topology.getBridge("bridge1");
            DtoRuleChain c1 = topology.getChain("chain1");
            DtoRuleChain c2 = topology.getChain("chain2");

            // Create a logical bridge port
            DtoLogicalBridgePort b1Lp1 = new DtoLogicalBridgePort();
            b1Lp1.setDeviceId(b.getId());
            b1Lp1 = dtoResource.postAndVerifyCreated(b.getPorts(),
                    APPLICATION_PORT_JSON, b1Lp1, DtoLogicalBridgePort.class);

            // Create a materialized bridge port
            DtoBridgePort b1Mp1 = new DtoBridgePort();
            b1Mp1.setDeviceId(b.getId());
            b1Mp1.setInboundFilterId(c1.getId());
            b1Mp1.setOutboundFilterId(c2.getId());
            b1Mp1 = dtoResource.postAndVerifyCreated(b.getPorts(),
                    APPLICATION_PORT_JSON, b1Mp1, DtoBridgePort.class);

            // List ports
            DtoBridgePort[] ports = dtoResource.getAndVerifyOk(b.getPorts(),
                    APPLICATION_PORT_JSON, DtoBridgePort[].class);
            assertEquals(2, ports.length);

            // Update port groups
            assertNull(b1Mp1.getPortGroupIDs());
            Set<UUID> portGroupIds = new HashSet<UUID>();
            portGroupIds.add(UUID.randomUUID());
            portGroupIds.add(UUID.randomUUID());
            b1Mp1.setPortGroupIDs(portGroupIds.toArray(new UUID[portGroupIds
                    .size()]));
            b1Mp1 = dtoResource.putAndVerifyNoContent(b1Mp1.getUri(),
                    APPLICATION_PORT_JSON, b1Mp1, DtoBridgePort.class);
            assertNotNull(b1Mp1.getPortGroupIDs());
            assertEquals(2, b1Mp1.getPortGroupIDs().length);

            // Update VIFs
            assertNull(b1Mp1.getVifId());
            UUID vifId = UUID.randomUUID();
            b1Mp1.setVifId(vifId);
            b1Mp1 = dtoResource.putAndVerifyNoContent(b1Mp1.getUri(),
                    APPLICATION_PORT_JSON, b1Mp1, DtoBridgePort.class);
            assertEquals(vifId, b1Mp1.getVifId());

            b1Mp1.setVifId(null);
            b1Mp1 = dtoResource.putAndVerifyNoContent(b1Mp1.getUri(),
                    APPLICATION_PORT_JSON, b1Mp1, DtoBridgePort.class);
            assertNull(b1Mp1.getVifId());

            // Update chains
            assertNotNull(b1Mp1.getInboundFilterId());
            assertNotNull(b1Mp1.getOutboundFilterId());
            b1Mp1.setInboundFilterId(null);
            b1Mp1.setOutboundFilterId(null);
            b1Mp1 = dtoResource.putAndVerifyNoContent(b1Mp1.getUri(),
                    APPLICATION_PORT_JSON, b1Mp1, DtoBridgePort.class);
            assertNull(b1Mp1.getInboundFilterId());
            assertNull(b1Mp1.getOutboundFilterId());

            b1Mp1.setInboundFilterId(c1.getId());
            b1Mp1.setOutboundFilterId(c2.getId());
            b1Mp1 = dtoResource.putAndVerifyNoContent(b1Mp1.getUri(),
                    APPLICATION_PORT_JSON, b1Mp1, DtoBridgePort.class);
            assertEquals(c1.getId(), b1Mp1.getInboundFilterId());
            assertEquals(c2.getId(), b1Mp1.getOutboundFilterId());

            // Swap
            b1Mp1.setInboundFilterId(c2.getId());
            b1Mp1.setOutboundFilterId(c1.getId());
            b1Mp1 = dtoResource.putAndVerifyNoContent(b1Mp1.getUri(),
                    APPLICATION_PORT_JSON, b1Mp1, DtoBridgePort.class);
            assertEquals(c2.getId(), b1Mp1.getInboundFilterId());
            assertEquals(c1.getId(), b1Mp1.getOutboundFilterId());

            // Delete the logical port.
            dtoResource.deleteAndVerifyNoContent(b1Lp1.getUri(),
                    APPLICATION_PORT_JSON);

            // Make sure it's no longer there
            dtoResource.getAndVerifyNotFound(b1Lp1.getUri(),
                    APPLICATION_PORT_JSON);

            // Delete the mat port.
            dtoResource.deleteAndVerifyNoContent(b1Mp1.getUri(),
                    APPLICATION_PORT_JSON);

            // Make sure it's no longer there
            dtoResource.getAndVerifyNotFound(b1Mp1.getUri(),
                    APPLICATION_PORT_JSON);

            // List and make sure not port found
            ports = dtoResource.getAndVerifyOk(b.getPorts(),
                    APPLICATION_PORT_JSON, DtoBridgePort[].class);
            assertEquals(0, ports.length);
        }
    }

    public static class TestRouterPortCrudSuccess extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;

        public TestRouterPortCrudSuccess() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() {
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a router
            DtoRouter r = new DtoRouter();
            r.setName("router1-name");
            r.setTenantId("tenant1-id");

            // Create a chain
            DtoRuleChain c1 = new DtoRuleChain();
            c1.setName("chain1-name");
            c1.setTenantId("tenant1-id");

            // Create another chain
            DtoRuleChain c2 = new DtoRuleChain();
            c2.setName("chain2-name");
            c2.setTenantId("tenant1-id");

            topology = new Topology.Builder(dtoResource)
                    .create("chain1", c1)
                    .create("chain2", c2)
                    .create("router1", r).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Test
        public void testCrudRouterPort() {

            // Get the router and chains
            DtoRouter r = topology.getRouter("router1");
            DtoRuleChain c1 = topology.getChain("chain1");
            DtoRuleChain c2 = topology.getChain("chain2");

            // Create a logical router port
            DtoLogicalRouterPort r1Lp1 = createLogicalRouterPort(null,
                    r.getId(), "10.0.0.0", 24, "10.0.0.1");
            r1Lp1 = dtoResource.postAndVerifyCreated(r.getPorts(),
                    APPLICATION_PORT_JSON, r1Lp1, DtoLogicalRouterPort.class);

            // Create a materialized router port
            DtoMaterializedRouterPort r1Mp1 = createMaterializedRouterPort(
                    null, r.getId(), "10.0.0.0", 24, "10.0.0.1", "10.0.0.2",
                    32, UUID.randomUUID(), c1.getId(), c2.getId());
            r1Mp1 = dtoResource.postAndVerifyCreated(r.getPorts(),
                    APPLICATION_PORT_JSON, r1Mp1,
                    DtoMaterializedRouterPort.class);

            // List ports
            DtoRouterPort[] ports = dtoResource.getAndVerifyOk(r.getPorts(),
                    APPLICATION_PORT_JSON, DtoRouterPort[].class);
            assertEquals(2, ports.length);

            // Update port groups
            assertNull(r1Mp1.getPortGroupIDs());
            Set<UUID> portGroupIds = new HashSet<UUID>();
            portGroupIds.add(UUID.randomUUID());
            portGroupIds.add(UUID.randomUUID());
            r1Mp1.setPortGroupIDs(portGroupIds.toArray(new UUID[portGroupIds
                    .size()]));
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                    APPLICATION_PORT_JSON, r1Mp1,
                    DtoMaterializedRouterPort.class);
            assertNotNull(r1Mp1.getPortGroupIDs());
            assertEquals(2, r1Mp1.getPortGroupIDs().length);

            // Update VIFs
            assertNull(r1Mp1.getVifId());
            UUID vifId = UUID.randomUUID();
            r1Mp1.setVifId(vifId);
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                    APPLICATION_PORT_JSON, r1Mp1,
                    DtoMaterializedRouterPort.class);
            assertEquals(vifId, r1Mp1.getVifId());

            r1Mp1.setVifId(null);
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                    APPLICATION_PORT_JSON, r1Mp1,
                    DtoMaterializedRouterPort.class);
            assertNull(r1Mp1.getVifId());

            // Update chains
            assertNotNull(r1Mp1.getInboundFilterId());
            assertNotNull(r1Mp1.getOutboundFilterId());
            r1Mp1.setInboundFilterId(null);
            r1Mp1.setOutboundFilterId(null);
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                    APPLICATION_PORT_JSON, r1Mp1,
                    DtoMaterializedRouterPort.class);
            assertNull(r1Mp1.getInboundFilterId());
            assertNull(r1Mp1.getOutboundFilterId());

            r1Mp1.setInboundFilterId(c1.getId());
            r1Mp1.setOutboundFilterId(c2.getId());
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                    APPLICATION_PORT_JSON, r1Mp1,
                    DtoMaterializedRouterPort.class);
            assertEquals(c1.getId(), r1Mp1.getInboundFilterId());
            assertEquals(c2.getId(), r1Mp1.getOutboundFilterId());

            // Swap
            r1Mp1.setInboundFilterId(c2.getId());
            r1Mp1.setOutboundFilterId(c1.getId());
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                    APPLICATION_PORT_JSON, r1Mp1,
                    DtoMaterializedRouterPort.class);
            assertEquals(c2.getId(), r1Mp1.getInboundFilterId());
            assertEquals(c1.getId(), r1Mp1.getOutboundFilterId());

            // Delete the logical port.
            dtoResource.deleteAndVerifyNoContent(r1Lp1.getUri(),
                    APPLICATION_PORT_JSON);

            // Make sure it's no longer there
            dtoResource.getAndVerifyNotFound(r1Lp1.getUri(),
                    APPLICATION_PORT_JSON);

            // Delete the mat port.
            dtoResource.deleteAndVerifyNoContent(r1Mp1.getUri(),
                    APPLICATION_PORT_JSON);

            // Make sure it's no longer there
            dtoResource.getAndVerifyNotFound(r1Mp1.getUri(),
                    APPLICATION_PORT_JSON);

            // List and make sure not port found
            ports = dtoResource.getAndVerifyOk(r.getPorts(),
                    APPLICATION_PORT_JSON, DtoRouterPort[].class);
            assertEquals(0, ports.length);
        }
    }

    public static class TestPortLinkSuccess extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;

        public TestPortLinkSuccess() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() {
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a router
            DtoRouter r1 = new DtoRouter();
            r1.setName("router1-name");
            r1.setTenantId("tenant1-id");

            // Create another router
            DtoRouter r2 = new DtoRouter();
            r2.setName("router2-name");
            r2.setTenantId("tenant1-id");

            // Create a bridge
            DtoBridge b1 = new DtoBridge();
            b1.setName("bridge1-name");
            b1.setTenantId("tenant1-id");

            // Create a logical router1 port
            DtoLogicalRouterPort r1Lp1 = createLogicalRouterPort(null, null,
                    "10.0.0.0", 24, "10.0.0.1");

            // Create another logical router1 port
            DtoLogicalRouterPort r1Lp2 = createLogicalRouterPort(null, null,
                    "192.168.0.0", 24, "192.168.0.1");

            // Create a logical router2 port
            DtoLogicalRouterPort r2Lp1 = createLogicalRouterPort(null, null,
                    "10.0.1.0", 24, "10.0.1.1");

            // Create another logical router2 port
            DtoLogicalRouterPort r2Lp2 = createLogicalRouterPort(null, null,
                    "192.168.1.0", 24, "192.168.1.1");

            topology = new Topology.Builder(dtoResource)
                    .create("router1", r1)
                    .create("router2", r2)
                    .create("bridge1", b1)
                    .create("router1", "router1Port1", r1Lp1)
                    .create("router1", "router1Port2", r1Lp2)
                    .create("router2", "router2Port1", r2Lp1)
                    .create("router2", "router2Port2", r2Lp2)
                    .create("bridge1", "bridge1Port1",
                            new DtoLogicalBridgePort())
                    .create("bridge1", "bridge1Port2",
                            new DtoLogicalBridgePort()).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Test
        public void testLinkUnlink() {

            DtoRouter router1 = topology.getRouter("router1");
            DtoRouter router2 = topology.getRouter("router2");
            DtoBridge bridge1 = topology.getBridge("bridge1");
            DtoLogicalRouterPort r1p1 = topology
                    .getLogRouterPort("router1Port1");
            DtoLogicalRouterPort r1p2 = topology
                    .getLogRouterPort("router1Port2");
            DtoLogicalRouterPort r2p1 = topology
                    .getLogRouterPort("router2Port1");
            DtoLogicalRouterPort r2p2 = topology
                    .getLogRouterPort("router2Port2");
            DtoLogicalBridgePort b1p1 = topology
                    .getLogBridgePort("bridge1Port1");
            DtoLogicalBridgePort b1p2 = topology
                    .getLogBridgePort("bridge1Port2");

            // Link router1 and router2
            dtoResource
                    .postAndVerifyStatus(r1p1.getLink(), APPLICATION_PORT_JSON,
                            "{\"peerId\": \"" + r2p1.getId() + "\"}",
                            Response.Status.NO_CONTENT.getStatusCode());

            // Link router1 and bridge1
            dtoResource
                    .postAndVerifyStatus(r1p2.getLink(), APPLICATION_PORT_JSON,
                            "{\"peerId\": \"" + b1p1.getId() + "\"}",
                            Response.Status.NO_CONTENT.getStatusCode());

            // Link bridge1 and router2
            dtoResource
                    .postAndVerifyStatus(b1p2.getLink(), APPLICATION_PORT_JSON,
                            "{\"peerId\": \"" + r2p2.getId() + "\"}",
                            Response.Status.NO_CONTENT.getStatusCode());

            // Get the peers
            DtoPort[] ports = dtoResource.getAndVerifyOk(
                    router1.getPeerPorts(), APPLICATION_PORT_JSON,
                    DtoPort[].class);
            assertNotNull(ports);
            assertEquals(2, ports.length);

            // Get the peers of router2
            ports = dtoResource.getAndVerifyOk(router2.getPeerPorts(),
                    APPLICATION_PORT_JSON, DtoPort[].class);
            assertNotNull(ports);
            assertEquals(2, ports.length);

            // Get the peers of bridge1
            ports = dtoResource.getAndVerifyOk(bridge1.getPeerPorts(),
                    APPLICATION_PORT_JSON, DtoPort[].class);
            assertNotNull(ports);
            assertEquals(2, ports.length);

            // Cannot link already linked ports
            dtoResource.postAndVerifyBadRequest(r1p1.getLink(),
                    APPLICATION_PORT_JSON, "{\"peerId\": \"" + r2p1.getId()
                            + "\"}");
            dtoResource.postAndVerifyBadRequest(r2p2.getLink(),
                    APPLICATION_PORT_JSON, "{\"peerId\": \"" + b1p2.getId()
                            + "\"}");

            // Cannot delete linked ports
            dtoResource.deleteAndVerifyBadRequest(r1p1.getUri(),
                    APPLICATION_PORT_JSON);
            dtoResource.deleteAndVerifyBadRequest(b1p1.getUri(),
                    APPLICATION_PORT_JSON);

            // Unlink
            dtoResource.postAndVerifyStatus(r1p1.getLink(),
                    APPLICATION_PORT_JSON, "{\"peerId\": " + null + "}",
                    Response.Status.NO_CONTENT.getStatusCode());
            dtoResource.postAndVerifyStatus(r1p2.getLink(),
                    APPLICATION_PORT_JSON, "{\"peerId\": " + null + "}",
                    Response.Status.NO_CONTENT.getStatusCode());
            dtoResource.postAndVerifyStatus(b1p1.getLink(),
                    APPLICATION_PORT_JSON, "{\"peerId\": " + null + "}",
                    Response.Status.NO_CONTENT.getStatusCode());
            dtoResource.postAndVerifyStatus(b1p2.getLink(),
                    APPLICATION_PORT_JSON, "{\"peerId\": " + null + "}",
                    Response.Status.NO_CONTENT.getStatusCode());
            dtoResource.postAndVerifyStatus(r2p1.getLink(),
                    APPLICATION_PORT_JSON, "{\"peerId\": " + null + "}",
                    Response.Status.NO_CONTENT.getStatusCode());
            dtoResource.postAndVerifyStatus(r2p2.getLink(),
                    APPLICATION_PORT_JSON, "{\"peerId\": " + null + "}",
                    Response.Status.NO_CONTENT.getStatusCode());

            // Delete all the ports
            dtoResource.deleteAndVerifyNoContent(r1p1.getUri(),
                    APPLICATION_PORT_JSON);
            dtoResource.deleteAndVerifyNoContent(r1p2.getUri(),
                    APPLICATION_PORT_JSON);
            dtoResource.deleteAndVerifyNoContent(r2p1.getUri(),
                    APPLICATION_PORT_JSON);
            dtoResource.deleteAndVerifyNoContent(r2p2.getUri(),
                    APPLICATION_PORT_JSON);
            dtoResource.deleteAndVerifyNoContent(b1p1.getUri(),
                    APPLICATION_PORT_JSON);
            dtoResource.deleteAndVerifyNoContent(b1p2.getUri(),
                    APPLICATION_PORT_JSON);

        }
    }
}