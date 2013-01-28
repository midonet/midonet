/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.network;

import com.midokura.midonet.api.VendorMediaType;
import com.midokura.midonet.api.rest_api.DtoWebResource;
import com.midokura.midonet.api.zookeeper.StaticMockDirectory;
import com.midokura.midonet.api.rest_api.FuncTest;
import com.midokura.midonet.api.rest_api.Topology;
import com.midokura.midonet.client.dto.*;
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

import static com.midokura.midonet.api.VendorMediaType.*;
import static org.junit.Assert.*;

@RunWith(Enclosed.class)
public class TestPort {

    public static DtoExteriorRouterPort createExteriorRouterPort(
            UUID id, UUID deviceId, String networkAddr, int networkLen,
            String portAddr, UUID vifId, UUID inboundFilterId,
            UUID outboundFilterId) {
        DtoExteriorRouterPort port = new DtoExteriorRouterPort();
        port.setId(id);
        port.setDeviceId(deviceId);
        port.setNetworkAddress(networkAddr);
        port.setNetworkLength(networkLen);
        port.setPortAddress(portAddr);
        port.setVifId(vifId);
        port.setInboundFilterId(inboundFilterId);
        port.setOutboundFilterId(outboundFilterId);

        return port;
    }

    public static DtoBridgePort createExteriorBridgePort(
            UUID id, UUID deviceId, UUID inboundFilterId,
            UUID outboundFilterId, UUID vifId) {
        DtoBridgePort port = new DtoBridgePort();
        port.setId(id);
        port.setDeviceId(deviceId);
        port.setInboundFilterId(inboundFilterId);
        port.setOutboundFilterId(outboundFilterId);
        port.setVifId(vifId);

        return port;
    }

    public static DtoInteriorRouterPort createInteriorRouterPort(UUID id,
            UUID deviceId, String networkAddr, int networkLen, 
            String portAddr) {
        DtoInteriorRouterPort port = new DtoInteriorRouterPort();
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
            DtoRouterPort badNetworkAddr = createInteriorRouterPort(null, null,
                    "badAddr", 24, "192.168.100.1");
            params.add(new Object[] { badNetworkAddr, "networkAddress" });

            // Bad port address
            DtoRouterPort badPortAddr = createInteriorRouterPort(null, null,
                    "10.0.0.0", 24, "badAddr");
            params.add(new Object[] { badPortAddr, "portAddress" });

            // Bad network len
            DtoRouterPort networkLenTooBig = createInteriorRouterPort(null,
                    null, "10.0.0.0", 33, "192.168.100.1");
            params.add(new Object[] { networkLenTooBig, "networkLength" });

            // Negative network len
            DtoRouterPort networkLenNegative = createInteriorRouterPort(null,
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
            c2.setTenantId("tenant1-id");

            // Create port groups
            DtoPortGroup pg1 = new DtoPortGroup();
            pg1.setTenantId("tenant1-id");
            pg1.setName("pg1-name");

            DtoPortGroup pg2 = new DtoPortGroup();
            pg2.setTenantId("tenant1-id");
            pg2.setName("pg2-name");

            topology = new Topology.Builder(dtoResource)
                    .create("chain1", c1)
                    .create("chain2", c2)
                    .create("bridge1", b)
                    .create("portGroup1", pg1)
                    .create("portGroup2", pg2).build();
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
            DtoPortGroup pg1 = topology.getPortGroup("portGroup1");
            DtoPortGroup pg2 = topology.getPortGroup("portGroup2");

            // Create a Interior bridge port
            DtoInteriorBridgePort b1Lp1 = new DtoInteriorBridgePort();
            b1Lp1.setDeviceId(b.getId());
            b1Lp1 = dtoResource.postAndVerifyCreated(b.getPorts(),
                    APPLICATION_PORT_JSON, b1Lp1, DtoInteriorBridgePort.class);

            // Create a Exterior bridge port
            DtoBridgePort b1Mp1 = new DtoBridgePort();
            b1Mp1.setDeviceId(b.getId());
            b1Mp1.setInboundFilterId(c1.getId());
            b1Mp1.setOutboundFilterId(c2.getId());
            b1Mp1 = dtoResource.postAndVerifyCreated(b.getPorts(),
                    APPLICATION_PORT_JSON, b1Mp1, DtoBridgePort.class);

            // List ports
            DtoBridgePort[] ports = dtoResource.getAndVerifyOk(b.getPorts(),
                    APPLICATION_PORT_COLLECTION_JSON, DtoBridgePort[].class);
            assertEquals(2, ports.length);

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

            // Delete the Interior port.
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
                    APPLICATION_PORT_COLLECTION_JSON, DtoBridgePort[].class);
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

            // Create port groups
            DtoPortGroup pg1 = new DtoPortGroup();
            pg1.setTenantId("tenant1-id");
            pg1.setName("pg1-name");

            DtoPortGroup pg2 = new DtoPortGroup();
            pg2.setTenantId("tenant1-id");
            pg2.setName("pg2-name");

            topology = new Topology.Builder(dtoResource)
                    .create("chain1", c1)
                    .create("chain2", c2)
                    .create("router1", r)
                    .create("portGroup1", pg1)
                    .create("portGroup2", pg2).build();
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
            DtoPortGroup pg1 = topology.getPortGroup("portGroup1");
            DtoPortGroup pg2 = topology.getPortGroup("portGroup2");

            // Create a Interior router port
            DtoInteriorRouterPort r1Lp1 = createInteriorRouterPort(null,
                    r.getId(), "10.0.0.0", 24, "10.0.0.1");
            r1Lp1 = dtoResource.postAndVerifyCreated(r.getPorts(),
                    APPLICATION_PORT_JSON, r1Lp1, DtoInteriorRouterPort.class);

            // Create a Exterior router port
            UUID vifId = UUID.randomUUID();
            DtoExteriorRouterPort r1Mp1 = createExteriorRouterPort(
                    null, r.getId(), "10.0.0.0", 24, "10.0.0.1",
                    vifId, c1.getId(), c2.getId());
            r1Mp1 = dtoResource.postAndVerifyCreated(r.getPorts(),
                    APPLICATION_PORT_JSON, r1Mp1,
                    DtoExteriorRouterPort.class);
            assertEquals(vifId, r1Mp1.getVifId());

            // List ports
            DtoRouterPort[] ports = dtoResource.getAndVerifyOk(r.getPorts(),
                    APPLICATION_PORT_COLLECTION_JSON, DtoRouterPort[].class);
            assertEquals(2, ports.length);

            // Update VIFs
            vifId = UUID.randomUUID();
            r1Mp1.setVifId(vifId);
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                    APPLICATION_PORT_JSON, r1Mp1,
                    DtoExteriorRouterPort.class);
            assertEquals(vifId, r1Mp1.getVifId());

            r1Mp1.setVifId(null);
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                    APPLICATION_PORT_JSON, r1Mp1,
                    DtoExteriorRouterPort.class);
            assertNull(r1Mp1.getVifId());

            // Update chains
            assertNotNull(r1Mp1.getInboundFilterId());
            assertNotNull(r1Mp1.getOutboundFilterId());
            r1Mp1.setInboundFilterId(null);
            r1Mp1.setOutboundFilterId(null);
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                    APPLICATION_PORT_JSON, r1Mp1,
                    DtoExteriorRouterPort.class);
            assertNull(r1Mp1.getInboundFilterId());
            assertNull(r1Mp1.getOutboundFilterId());

            r1Mp1.setInboundFilterId(c1.getId());
            r1Mp1.setOutboundFilterId(c2.getId());
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                    APPLICATION_PORT_JSON, r1Mp1,
                    DtoExteriorRouterPort.class);
            assertEquals(c1.getId(), r1Mp1.getInboundFilterId());
            assertEquals(c2.getId(), r1Mp1.getOutboundFilterId());

            // Swap
            r1Mp1.setInboundFilterId(c2.getId());
            r1Mp1.setOutboundFilterId(c1.getId());
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                    APPLICATION_PORT_JSON, r1Mp1,
                    DtoExteriorRouterPort.class);
            assertEquals(c2.getId(), r1Mp1.getInboundFilterId());
            assertEquals(c1.getId(), r1Mp1.getOutboundFilterId());

            // Delete the Interior port.
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
                    APPLICATION_PORT_COLLECTION_JSON, DtoRouterPort[].class);
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

            // Create a Interior router1 port
            DtoInteriorRouterPort r1Lp1 = createInteriorRouterPort(null, null,
                    "10.0.0.0", 24, "10.0.0.1");

            // Create another Interior router1 port
            DtoInteriorRouterPort r1Lp2 = createInteriorRouterPort(null, null,
                    "192.168.0.0", 24, "192.168.0.1");

            // Create a Interior router2 port
            DtoInteriorRouterPort r2Lp1 = createInteriorRouterPort(null, null,
                    "10.0.1.0", 24, "10.0.1.1");

            // Create another Interior router2 port
            DtoInteriorRouterPort r2Lp2 = createInteriorRouterPort(null, null,
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
                            new DtoInteriorBridgePort())
                    .create("bridge1", "bridge1Port2",
                            new DtoInteriorBridgePort()).build();
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
            DtoInteriorRouterPort r1p1 = topology
                    .getIntRouterPort("router1Port1");
            DtoInteriorRouterPort r1p2 = topology
                    .getIntRouterPort("router1Port2");
            DtoInteriorRouterPort r2p1 = topology
                    .getIntRouterPort("router2Port1");
            DtoInteriorRouterPort r2p2 = topology
                    .getIntRouterPort("router2Port2");
            DtoInteriorBridgePort b1p1 = topology
                    .getIntBridgePort("bridge1Port1");
            DtoInteriorBridgePort b1p2 = topology
                    .getIntBridgePort("bridge1Port2");

            // Link router1 and router2
            DtoLink link = new DtoLink();
            link.setPeerId(r2p1.getId());
            dtoResource
                    .postAndVerifyStatus(r1p1.getLink(),
                            APPLICATION_PORT_LINK_JSON, link,
                            Response.Status.CREATED.getStatusCode());

            // Link router1 and bridge1
            link = new DtoLink();
            link.setPeerId(b1p1.getId());
            dtoResource
                    .postAndVerifyStatus(r1p2.getLink(),
                            APPLICATION_PORT_LINK_JSON, link,
                            Response.Status.CREATED.getStatusCode());

            // Link bridge1 and router2
            link = new DtoLink();
            link.setPeerId(r2p2.getId());
            dtoResource
                    .postAndVerifyStatus(b1p2.getLink(),
                            APPLICATION_PORT_LINK_JSON, link,
                            Response.Status.CREATED.getStatusCode());

            // Get the peers
            DtoPort[] ports = dtoResource.getAndVerifyOk(
                    router1.getPeerPorts(), APPLICATION_PORT_COLLECTION_JSON,
                    DtoPort[].class);
            assertNotNull(ports);
            assertEquals(2, ports.length);

            // Get the peers of router2
            ports = dtoResource.getAndVerifyOk(router2.getPeerPorts(),
                    APPLICATION_PORT_COLLECTION_JSON, DtoPort[].class);
            assertNotNull(ports);
            assertEquals(2, ports.length);

            // Get the peers of bridge1
            ports = dtoResource.getAndVerifyOk(bridge1.getPeerPorts(),
                    APPLICATION_PORT_COLLECTION_JSON, DtoPort[].class);
            assertNotNull(ports);
            assertEquals(2, ports.length);

            // Cannot link already linked ports
            link = new DtoLink();
            link.setPeerId(r2p1.getId());
            dtoResource.postAndVerifyBadRequest(r1p1.getLink(),
                    APPLICATION_PORT_LINK_JSON, link);
            link = new DtoLink();
            link.setPeerId(r2p1.getId());
            dtoResource.postAndVerifyBadRequest(b1p2.getLink(),
                    APPLICATION_PORT_LINK_JSON, link);

            // Cannot delete linked ports
            dtoResource.deleteAndVerifyBadRequest(r1p1.getUri(),
                    APPLICATION_PORT_JSON);
            dtoResource.deleteAndVerifyBadRequest(b1p1.getUri(),
                    APPLICATION_PORT_JSON);

            // Unlink
            dtoResource.deleteAndVerifyStatus(r1p1.getLink(),
                    APPLICATION_PORT_LINK_JSON,
                    Response.Status.NO_CONTENT.getStatusCode());
            dtoResource.deleteAndVerifyStatus(r1p2.getLink(),
                    APPLICATION_PORT_LINK_JSON,
                    Response.Status.NO_CONTENT.getStatusCode());
            dtoResource.deleteAndVerifyStatus(b1p1.getLink(),
                    APPLICATION_PORT_LINK_JSON,
                    Response.Status.NO_CONTENT.getStatusCode());
            dtoResource.deleteAndVerifyStatus(b1p2.getLink(),
                    APPLICATION_PORT_LINK_JSON,
                    Response.Status.NO_CONTENT.getStatusCode());
            dtoResource.deleteAndVerifyStatus(r2p1.getLink(),
                    APPLICATION_PORT_LINK_JSON,
                    Response.Status.NO_CONTENT.getStatusCode());
            dtoResource.deleteAndVerifyStatus(r2p2.getLink(),
                    APPLICATION_PORT_LINK_JSON,
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

    public static class TestExteriorBridgePortUpdateSuccess extends
            JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;

        public TestExteriorBridgePortUpdateSuccess() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() {
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a bridge
            DtoBridge b1 = new DtoBridge();
            b1.setName("bridge1-name");
            b1.setTenantId("tenant1-id");

            // Create a port
            DtoBridgePort port1 = createExteriorBridgePort(null, null,
                    null, null, null);

            topology = new Topology.Builder(dtoResource)
                    .create("bridge1", b1)
                    .create("bridge1", "port1", port1).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Test
        public void testUpdate() throws Exception {

            DtoBridgePort origPort = topology.getExtBridgePort("port1");

            assertNull(origPort.getVifId());

            origPort.setVifId(UUID.randomUUID());
            DtoBridgePort newPort = dtoResource.putAndVerifyNoContent(
                    origPort.getUri(),
                    APPLICATION_PORT_JSON, origPort,
                    DtoBridgePort.class);

            assertEquals(origPort.getVifId(), newPort.getVifId());

        }

    }

    public static class TestPortGroupMembershipSuccess extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;

        public TestPortGroupMembershipSuccess() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() {
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a port group
            DtoPortGroup pg1 = new DtoPortGroup();
            pg1.setName("pg1-name");
            pg1.setTenantId("tenant1-id");

            // Create a bridge
            DtoBridge bg1 = new DtoBridge();
            bg1.setName("bg1-name");
            bg1.setTenantId("tenant1-id");

            // Create a port
            DtoBridgePort bridgePort = createExteriorBridgePort(null,
                    null, null, null, null);

            topology = new Topology.Builder(dtoResource)
                    .create("pg1", pg1)
                    .create("bg1", bg1)
                    .create("bg1", "port1", bridgePort).build();
        }

        @Test
        public void testCrudSuccess() throws Exception {

            DtoPortGroup pg1 = topology.getPortGroup("pg1");
            DtoBridgePort port1 = topology.getExtBridgePort("port1");

            // List and make sure there is no membership
            DtoPortGroupPort[] portGroupPorts = dtoResource.getAndVerifyOk(
                    pg1.getPorts(),
                    APPLICATION_PORTGROUP_PORT_COLLECTION_JSON,
                    DtoPortGroupPort[].class);
            assertEquals(0, portGroupPorts.length);

            // Add a port to a group
            DtoPortGroupPort portGroupPort = new DtoPortGroupPort();
            portGroupPort.setPortGroupId(pg1.getId());
            portGroupPort.setPortId(port1.getId());
            portGroupPort = dtoResource.postAndVerifyCreated(pg1.getPorts(),
                    APPLICATION_PORTGROUP_PORT_JSON,
                    portGroupPort, DtoPortGroupPort.class);

            // List all.  There should be one now
            portGroupPorts = dtoResource.getAndVerifyOk(
                    pg1.getPorts(),
                    APPLICATION_PORTGROUP_PORT_COLLECTION_JSON,
                    DtoPortGroupPort[].class);
            assertEquals(1, portGroupPorts.length);

            // List from port's URI
            DtoPortGroup[] portGroups = dtoResource.getAndVerifyOk(
                    port1.getPortGroups(),
                    APPLICATION_PORTGROUP_COLLECTION_JSON,
                    DtoPortGroup[].class);
            assertEquals(1, portGroups.length);

            // Delete the membership
            dtoResource.deleteAndVerifyNoContent(portGroupPort.getUri(),
                    APPLICATION_PORTGROUP_PORT_JSON);

            // List once again, and make sure it's not there
            portGroupPorts = dtoResource.getAndVerifyOk(
                    pg1.getPorts(),
                    APPLICATION_PORTGROUP_PORT_COLLECTION_JSON,
                    DtoPortGroupPort[].class);
            assertEquals(0, portGroupPorts.length);

        }

    }

}
