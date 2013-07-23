/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import java.net.InetAddress;
import java.net.URI;
import java.util.*;
import javax.ws.rs.core.Response;


import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.Injector;
import com.google.inject.Guice;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import org.midonet.api.host.rest_api.HostTopology;
import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.Topology;
import org.midonet.api.serialization.SerializationModule;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.MidonetApi;
import org.midonet.client.dto.*;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.version.guice.VersionModule;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.midonet.api.VendorMediaType.*;

@RunWith(Enclosed.class)
public class TestPort {
    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";

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

    public static DtoVlanBridgeInteriorPort createVlanBridgeInteriorPort(
        UUID id, UUID deviceId, Short vlanId) {
        DtoVlanBridgeInteriorPort port = new DtoVlanBridgeInteriorPort();
        port.setId(id);
        port.setDeviceId(deviceId);
        port.setVlanId(vlanId);
        return port;
    }

    public static DtoVlanBridgeTrunkPort createVlanBridgeTrunkPort(
        UUID id, UUID deviceId, UUID vifId) {
        DtoVlanBridgeTrunkPort port = new DtoVlanBridgeTrunkPort();
        port.setId(id);
        port.setDeviceId(deviceId);
        port.setVifId(vifId);
        return port;
    }

    /**
     * Create a client-side DTO object of a host-interface-port filled with
     * specified parameters.
     *
     * @param hostId        an UUID of the host
     * @param interfaceName a Name of the interface
     * @param portId        an UUID of the port that contains the interface
     * @return              the client-side DTO object of the
     *                      host-interface-port binding
     */
    public static DtoHostInterfacePort createHostInterfacePort(UUID hostId,
            String interfaceName, UUID portId) {
        DtoHostInterfacePort hostInterfacePort = new DtoHostInterfacePort();
        hostInterfacePort.setHostId(hostId);
        hostInterfacePort.setInterfaceName(interfaceName);
        hostInterfacePort.setPortId(portId);
        return hostInterfacePort;
    }

    /**
     * Create a client-side DTO object of a host filled with specified
     * parameters.
     *
     * @param id        an UUID of the host to be created
     * @param name      a name of the host to be created
     * @param alive     an aliveness of the host to be created
     * @param addresses an array contains addresses' string representation of a
     *                  host to be created
     * @return          the client-side DTO object of the host
     */
    public static DtoHost createHost(UUID id, String name, boolean alive,
            String[] addresses) {
        DtoHost host = new DtoHost();
        host.setId(id);
        host.setName(name);
        host.setAlive(alive);
        host.setAddresses(addresses);
        return host;
    }

    /**
     * Create a client-side DTO object of an interface filled with specified
     * parameters.
     *
     * @param id        an UUID of an interface to be created
     * @param hostId    an UUID of a host which contains an interface to be
     *                  created
     * @param name      a name of an interface to be created
     * @param mac       a string representation of the MAC address of an
     *                  interface to be created
     * @param mtu       a MTU of an interface to be created
     * @param status    a status of an interface to be created
     * @param type      a type of an interface to be created
     * @param addresses an array contains addresses' InetAddress representation
     *                  of an interface to be created
     * @return          the client-side DTO object of the interface
     */
    public static DtoInterface createInterface(UUID id, UUID hostId,
            String name, String mac, int mtu, int status, DtoInterface.Type type,
            InetAddress[] addresses) {
        DtoInterface _interface = new DtoInterface();
        _interface.setId(id);
        _interface.setHostId(hostId);
        _interface.setName(name);
        _interface.setMac(mac);
        _interface.setMtu(mtu);
        _interface.setStatus(status);
        _interface.setType(type);
        _interface.setAddresses(addresses);
        return _interface;
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
        public void testCrudBridgePortSameVLAN() {

            // Get the bridge and chains
            DtoBridge b = topology.getBridge("bridge1");
            DtoRuleChain c1 = topology.getChain("chain1");
            DtoRuleChain c2 = topology.getChain("chain2");

            // Create an Interior bridge port
            DtoInteriorBridgePort b1Lp1 = new DtoInteriorBridgePort();
            short vlanId = 2727;
            b1Lp1.setDeviceId(b.getId());
            b1Lp1.setVlanId(vlanId);
            b1Lp1 = dtoResource.postAndVerifyCreated(b.getPorts(),
                    APPLICATION_PORT_JSON, b1Lp1, DtoInteriorBridgePort.class);

            // This test can't be used because MockDirectory.multi doesn't handle failed ops

            // Create another Interior bridge port with same VLAN, should fail
            //DtoInteriorBridgePort b1Lp2 = new DtoInteriorBridgePort();
            //b1Lp2.setDeviceId(b.getId());
            //b1Lp2.setVlanId(vlanId);
            //dtoResource.postAndVerifyBadRequest(b.getPorts(),
            //        APPLICATION_PORT_JSON, b1Lp2);

            // Delete the initial port
            dtoResource.deleteAndVerifyNoContent(b1Lp1.getUri(),
                    APPLICATION_PORT_JSON);

            // Should now be able to add a port with same VLAN
            DtoInteriorBridgePort b1Lp2 = new DtoInteriorBridgePort();
            b1Lp2.setDeviceId(b.getId());
            b1Lp2.setVlanId(vlanId);
            b1Lp2 = dtoResource.postAndVerifyCreated(b.getPorts(),
                    APPLICATION_PORT_JSON, b1Lp2, DtoInteriorBridgePort.class);

            // Try delete the bridge, test it deletes cleanly
            dtoResource.deleteAndVerifyNoContent(b.getUri(), APPLICATION_BRIDGE_JSON);
        }

        @Test
        public void testCrudBridgePort() {

            // Get the bridge and chains
            DtoBridge b = topology.getBridge("bridge1");
            DtoRuleChain c1 = topology.getChain("chain1");
            DtoRuleChain c2 = topology.getChain("chain2");

            // Create a Interior bridge port
            DtoInteriorBridgePort b1Lp1 = new DtoInteriorBridgePort();
            short vlanId = 666;
            b1Lp1.setDeviceId(b.getId());
            b1Lp1.setVlanId(vlanId);
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

            // Create a Interior router port
            DtoInteriorRouterPort r1Lp1 = createInteriorRouterPort(null,
                                                                   r.getId(),
                                                                   "10.0.0.0",
                                                                   24,
                                                                   "10.0.0.1");
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
                                                      APPLICATION_PORT_JSON,
                                                      r1Mp1,
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

            // Create a vlan bridge
            DtoVlanBridge vb = new DtoVlanBridge();
            vb.setName("vlan-bride1-name");
            vb.setTenantId("tenant1-id");

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
                                                                   "192.168.1.0",
                                                                   24,
                                                                   "192.168.1.1");

            DtoVlanBridgeTrunkPort vbTp1 =
                createVlanBridgeTrunkPort(null, null, null);
            DtoVlanBridgeTrunkPort vbTp2 =
                createVlanBridgeTrunkPort(null, null, null);

            DtoVlanBridgeInteriorPort vbIp1 =
                createVlanBridgeInteriorPort(null, null, (short)101);
            DtoVlanBridgeInteriorPort vbIp2 =
                createVlanBridgeInteriorPort(null, null, (short)202);

            topology = new Topology.Builder(dtoResource)
                    .create("router1", r1)
                    .create("router2", r2)
                    .create("bridge1", b1)
                    .create("vlan-bridge1", vb)
                    .create("vlan-bridge1", "vbIntPort1", vbIp1)
                    .create("vlan-bridge1", "vbIntPort2", vbIp2)
                    .create("vlan-bridge1", "vbTrunkPort1", vbTp1)
                    .create("vlan-bridge1", "vbTrunkPort2", vbTp2)
                    .create("router1", "router1Port1", r1Lp1)
                    .create("router1", "router1Port2", r1Lp2)
                    .create("router2", "router2Port1", r2Lp1)
                    .create("router2", "router2Port2", r2Lp2)
                    .create("bridge1", "bridge1Port1",
                            new DtoInteriorBridgePort())
                    .create("bridge1", "bridge1Port2",
                            new DtoInteriorBridgePort())
                // These two we'll link to the Vlan Bridge
                    .create("bridge1", "bridge1Port3",
                            new DtoInteriorBridgePort())
                    .create("bridge1", "bridge1Port4",
                            new DtoInteriorBridgePort())
                    .build();
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
            DtoVlanBridge vlanBridge = topology.getVlanBridge("vlan-bridge1");
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
            DtoInteriorBridgePort b1p3 = topology
                    .getIntBridgePort("bridge1Port3");
            DtoInteriorBridgePort b1p4 = topology
                    .getIntBridgePort("bridge1Port4");
            DtoVlanBridgeInteriorPort vbIp1 = topology
                    .getVlanBridgeIntPort("vbIntPort1");
            DtoVlanBridgeInteriorPort vbIp2 = topology
                    .getVlanBridgeIntPort("vbIntPort2");
            DtoVlanBridgeTrunkPort vbTp1 = topology
                .getVlanBridgeTrunkPort("vbTrunkPort1");
            DtoVlanBridgeTrunkPort vbTp2 = topology
                .getVlanBridgeTrunkPort("vbTrunkPort2");

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

            // Link vlan-bridge and br1 on VLAN 101
            link = new DtoLink();
            link.setPeerId(b1p3.getId());
            dtoResource.postAndVerifyStatus(
                vbIp1.getLink(),
                APPLICATION_PORT_LINK_JSON, link,
                Response.Status.CREATED.getStatusCode());

            // Link vlan-bridge and br1 on VLAN 202
            link = new DtoLink();
            link.setPeerId(b1p4.getId());
            dtoResource.postAndVerifyStatus(
                vbIp2.getLink(),
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
            assertEquals(4, ports.length); // 2 rt, 2 vlan br.

            // Get the peers of vlan bridge
            ports = dtoResource.getAndVerifyOk(vlanBridge.getInteriorPorts(),
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

            // Cannot link already linked ports
            link = new DtoLink();
            link.setPeerId(b1p3.getId());
            dtoResource.postAndVerifyBadRequest(vbIp1.getLink(),
                    APPLICATION_PORT_LINK_JSON, link);

            // Cannot link ports in a vlan bridge
            link = new DtoLink();
            link.setPeerId(vbTp1.getId());
            dtoResource.postAndVerifyBadRequest(vbIp2.getLink(),
                    APPLICATION_PORT_LINK_JSON, link);

            // Cannot delete linked ports
            dtoResource.deleteAndVerifyBadRequest(r1p1.getUri(), APPLICATION_PORT_JSON);
            dtoResource.deleteAndVerifyBadRequest(b1p1.getUri(), APPLICATION_PORT_JSON);
            dtoResource.deleteAndVerifyBadRequest(vbIp1.getUri(), APPLICATION_PORT_JSON);

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
            dtoResource.deleteAndVerifyStatus(vbIp1.getLink(),
                    APPLICATION_PORT_LINK_JSON,
                    Response.Status.NO_CONTENT.getStatusCode());
            dtoResource.deleteAndVerifyStatus(vbIp2.getLink(),
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
            dtoResource.deleteAndVerifyNoContent(b1p3.getUri(),
                    APPLICATION_PORT_JSON);
            dtoResource.deleteAndVerifyNoContent(b1p4.getUri(),
                    APPLICATION_PORT_JSON);
            dtoResource.deleteAndVerifyNoContent(vbIp1.getUri(),
                    APPLICATION_PORT_JSON);
            dtoResource.deleteAndVerifyNoContent(vbIp2.getUri(),
                    APPLICATION_PORT_JSON);
            dtoResource.deleteAndVerifyNoContent(vbTp1.getUri(),
                    APPLICATION_PORT_JSON);
            dtoResource.deleteAndVerifyNoContent(vbTp2.getUri(),
                    APPLICATION_PORT_JSON);
        }

        public static class TestVlanBridgeInteriorPortCrudSuccess extends JerseyTest {

            private DtoWebResource dtoResource;
            private Topology topology;

            public TestVlanBridgeInteriorPortCrudSuccess() {
                super(FuncTest.appDesc);
            }

            @Before
            public void setUp() {
                WebResource resource = resource();
                dtoResource = new DtoWebResource(resource);

                // Create a vlan bridge
                DtoVlanBridge b = new DtoVlanBridge();
                b.setName("vlan-bridge1-name");
                b.setTenantId("tenant1-id");

                // Create a couple of ports
                DtoVlanBridgeInteriorPort p1 = createVlanBridgeInteriorPort(
                    null, null, null);
                DtoVlanBridgeTrunkPort p2 = createVlanBridgeTrunkPort(
                    null, null, null);

                topology = new Topology.Builder(dtoResource)
                    .create("vlan-bridge1", b)
                    .create("vlan-bridge1", "interior-port", p1)
                    .create("vlan-bridge1", "trunk-port", p2)
                    .build();
            }

            @After
            public void resetDirectory() throws Exception {
                StaticMockDirectory.clearDirectoryInstance();
            }

            @Test
            public void testCrudVlanBridgePort() {

                // Get the vlan bridge
                DtoVlanBridge b = topology.getVlanBridge("vlan-bridge1");

                // Create a Interior bridge port
                DtoVlanBridgeInteriorPort b1Lp1 = new DtoVlanBridgeInteriorPort();
                b1Lp1.setDeviceId(b.getId());
                b1Lp1.setVlanId((short) 100);
                b1Lp1 = dtoResource.postAndVerifyCreated(b.getInteriorPorts(),
                                        APPLICATION_PORT_JSON, b1Lp1,
                                        DtoVlanBridgeInteriorPort.class);

                // Create an exterior vlan bridge trunk port
                DtoVlanBridgeTrunkPort b1Tp1 = new DtoVlanBridgeTrunkPort();
                b1Tp1.setDeviceId(b.getId());
                b1Tp1 = dtoResource.postAndVerifyCreated(b.getTrunkPorts(),
                                                 APPLICATION_PORT_JSON, b1Tp1,
                                                 DtoVlanBridgeTrunkPort.class);

                assertEquals(b.getId(), b1Tp1.getDeviceId());

                // List ports
                DtoVlanBridgeInteriorPort[] ports = dtoResource.getAndVerifyOk(
                                            b.getInteriorPorts(),
                                            APPLICATION_PORT_COLLECTION_JSON,
                                            DtoVlanBridgeInteriorPort[].class);
                assertEquals(1, ports.length);

                DtoVlanBridgeTrunkPort[] tPorts = dtoResource.getAndVerifyOk(
                    b.getTrunkPorts(),
                    APPLICATION_PORT_COLLECTION_JSON,
                    DtoVlanBridgeTrunkPort[].class);
                assertEquals(1, tPorts.length);

                // Update VIFs
                assertNull(b1Tp1.getVifId());
                UUID vifId = UUID.randomUUID();
                b1Tp1.setVifId(vifId);
                b1Tp1 = dtoResource.putAndVerifyNoContent(b1Tp1.getUri(),
                                                  APPLICATION_PORT_JSON, b1Tp1,
                                                  DtoVlanBridgeTrunkPort.class);
                assertEquals(vifId, b1Tp1.getVifId());

                b1Tp1.setVifId(null);
                b1Tp1 = dtoResource.putAndVerifyNoContent(b1Tp1.getUri(),
                                                  APPLICATION_PORT_JSON, b1Tp1,
                                                  DtoVlanBridgeTrunkPort.class);
                assertNull(b1Tp1.getVifId());

                // Delete the Interior port.
                dtoResource.deleteAndVerifyNoContent(b1Lp1.getUri(),
                                                     APPLICATION_PORT_JSON);

                // Make sure it's no longer there
                dtoResource.getAndVerifyNotFound(b1Lp1.getUri(),
                                                 APPLICATION_PORT_JSON);

                // Delete the mat port.
                dtoResource.deleteAndVerifyNoContent(b1Tp1.getUri(),
                                                     APPLICATION_PORT_JSON);

                // Make sure it's no longer there
                dtoResource.getAndVerifyNotFound(b1Tp1.getUri(),
                                                 APPLICATION_PORT_JSON);

                // List and make sure no port is found
                tPorts = dtoResource.getAndVerifyOk(b.getTrunkPorts(),
                                           APPLICATION_PORT_COLLECTION_JSON,
                                           DtoVlanBridgeTrunkPort[].class);
                assertEquals(0, ports.length);

                DtoVlanBridgeInteriorPort[] lPorts =
                    dtoResource.getAndVerifyOk(b.getInteriorPorts(),
                                           APPLICATION_PORT_COLLECTION_JSON,
                                           DtoVlanBridgeInteriorPort[].class);
                assertEquals(0, lPorts.length);
            }
        }
    }

    public static class TestVlanBridgeTrunkPortUpdateSuccess extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;

        public TestVlanBridgeTrunkPortUpdateSuccess() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() {
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a vlan bridge
            DtoVlanBridge b1 = new DtoVlanBridge();
            b1.setName("vlan-bridge1-name");
            b1.setTenantId("tenant1-id");

            // Create a port
            DtoVlanBridgeTrunkPort port1 = createVlanBridgeTrunkPort(
                null, null, null);

            topology = new Topology.Builder(dtoResource)
                .create("vlan-bridge1", b1)
                .create("vlan-bridge1", "port1", port1).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Test
        public void testUpdate() throws Exception {

            DtoVlanBridgeTrunkPort origPort = topology
                                              .getVlanBridgeTrunkPort("port1");

            assertNull(origPort.getVifId());

            origPort.setVifId(UUID.randomUUID());
            DtoVlanBridgeTrunkPort newPort = dtoResource.putAndVerifyNoContent(
                origPort.getUri(), APPLICATION_PORT_JSON, origPort,
                DtoVlanBridgeTrunkPort.class);

            assertEquals(origPort.getVifId(), newPort.getVifId());

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

    /**
     * Test cases for the port-host-interface bindings can be retrieved from
     * the port side when the bindings are already created in the host side.
     */
    public static class TestPortHostInterfaceGetSuccess extends JerseyTest {
        private DtoWebResource dtoResource;
        private Topology topology;
        private HostTopology hostTopology;
        private HostZkManager hostManager;
        private MidonetApi api;
        private Injector injector = null;

        private DtoRouter router1;
        private DtoBridge bridge1;
        private DtoVlanBridge vlanBridge1;
        private DtoExteriorRouterPort port1;
        private DtoBridgePort port2;
        private DtoVlanBridgeTrunkPort trunkPort1;
        private DtoHost host1, host2, host3;
        private DtoInterface interface1, interface2, interface3;
        private DtoHostInterfacePort hostInterfacePort1, hostInterfacePort2,
                                     hostInterfacePort3;

        /**
         * Constructor to initialize the test cases with the configuration.
         */
        public TestPortHostInterfaceGetSuccess() {
            super(FuncTest.appDesc);
        }

        public class TestModule extends AbstractModule {

            private final String basePath;

            public TestModule(String basePath) {
                this.basePath = basePath;
            }

            @Override
            protected void configure() {
                bind(PathBuilder.class).toInstance(new PathBuilder(basePath));
            }

            @Provides
            @Singleton
            public Directory provideDirectory() {
                Directory directory = StaticMockDirectory.getDirectoryInstance();
                return directory;
            }

            @Provides @Singleton
            public ZkManager provideZkManager(Directory directory) {
                return new ZkManager(directory);
            }

            @Provides @Singleton
            public HostZkManager provideHostZkManager(ZkManager zkManager,
                                                      PathBuilder paths,
                                                      Serializer serializer) {
                return new HostZkManager(zkManager, paths, serializer);
            }
        }

        /**
         * Set up the logical network topology and the host topology.
         *
         * @throws Exception
         */
        @Before
        @Override
        public void setUp() throws Exception {
            injector = Guice.createInjector(
                    new VersionModule(),
                    new SerializationModule(),
                    new TestModule(ZK_ROOT_MIDOLMAN));
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);
            hostManager = injector.getInstance(HostZkManager.class);

            // Creating the topology for the exterior **router** port and the
            // interface.
            // Create a router.
            router1 = new DtoRouter();
            router1.setName("router1-name");
            router1.setTenantId("tenant1-id");
            // Create an exterior router port on the router.
            port1 = createExteriorRouterPort(
                    UUID.randomUUID(), router1.getId(), "10.0.0.0", 24,
                    "10.0.0.1", null, null, null);
            // Creating the topology for the exterior **bridge** port and the
            // interface.
            // Create a vlan bridge
            vlanBridge1 = new DtoVlanBridge();
            vlanBridge1.setName("vlan-bridge1");
            vlanBridge1.setTenantId("vlan-bridge1-name");
            // Create a bridge.
            bridge1 = new DtoBridge();
            bridge1.setName("bridge1");
            bridge1.setTenantId("bridge1-name");
            // Create an exterior bridge port on the bridge.
            port2 = createExteriorBridgePort(UUID.randomUUID(),
                    bridge1.getId(), null, null, null);
            // Create a trunk port on the vlan bridge.
            trunkPort1 = createVlanBridgeTrunkPort(UUID.randomUUID(),
                                                   vlanBridge1.getId(),
                                                   null);
            topology = new Topology.Builder(dtoResource)
                    .create("router1", router1)
                    .create("router1", "port1", port1)
                    .create("bridge1", bridge1)
                    .create("bridge1", "port2", port2)
                    .create("vlan-bridge1", vlanBridge1)
                    .create("vlan-bridge1", "trunk1", trunkPort1)
                    .build();

            // Create a host that contains an interface bound to the router port.
            host1 = createHost(UUID.randomUUID(), "host1", true, null);
            // Create an interface to be bound to the port.
            interface1 = createInterface(UUID.randomUUID(),
                    host1.getId(), "interface1", "01:23:45:67:89:01", 1500,
                    0x01, DtoInterface.Type.Virtual,
                    new InetAddress[]{
                            InetAddress.getByAddress(new byte[]{10, 10, 10, 1})
                    });
            // Create a host that contains an interface bound to the bridge port.
            host2 = createHost(UUID.randomUUID(), "host2", true, null);
            // Create an interface to be bound to the port.
            interface2 = createInterface(UUID.randomUUID(),
                                         host2.getId(), "interface2",
                                         "01:23:45:67:89:01", 1500,
                                         0x01, DtoInterface.Type.Virtual,
                                         new InetAddress[]{
                                             InetAddress.getByAddress(
                                                 new byte[]{10, 10, 10, 2})
                                         });
            // Create a host that contains an interface bound to the bridge port.
            host3 = createHost(UUID.randomUUID(), "host3", true, null);
            // Create an interface to be bound to the trunk port
            interface3 = createInterface(UUID.randomUUID(),
                                         host3.getId(), "interface3",
                                         "11:22:33:44:55:66", 1500,
                                         0x01, DtoInterface.Type.Virtual,
                                         new InetAddress[]{
                                             InetAddress.getByAddress(
                                                 new byte[]{10, 10, 10, 3})
                                         });
            port1 = topology.getExtRouterPort("port1");
            port2 = topology.getExtBridgePort("port2");
            trunkPort1 = topology.getVlanBridgeTrunkPort("trunk1");

            // Create a host-interface-port binding finally.
            hostInterfacePort1 = createHostInterfacePort(
                    host1.getId(), interface1.getName(), port1.getId());
            // Create a host-interface-port binding finally.
            hostInterfacePort2 = createHostInterfacePort(
                    host2.getId(), interface2.getName(), port2.getId());
            hostInterfacePort3 = createHostInterfacePort(
                host3.getId(), interface3.getName(), trunkPort1.getId());
            hostTopology = new HostTopology.Builder(dtoResource, hostManager)
                    .create(host1.getId(), host1)
                    .create(hostInterfacePort1.getHostId(),
                            hostInterfacePort1.getPortId(), hostInterfacePort1)
                    .create(host2.getId(), host2)
                    .create(hostInterfacePort2.getHostId(),
                            hostInterfacePort2.getPortId(), hostInterfacePort2)
                    .create(host3.getId(), host3)
                    .create(hostInterfacePort3.getHostId(),
                            hostInterfacePort3.getPortId(), hostInterfacePort3)
                    .build();

            URI baseUri = resource().getURI();
            api = new MidonetApi(baseUri.toString());
            api.enableLogging();
        }

        /**
         * Teardown method to clean up the mock directory at the end of tests
         * defined in this class.
         *
         * @throws Exception
         */
        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        /**
         * Test that the router's port has the appropriate host-interface-port
         * binding.
         *
         * @throws Exception
         */
        @Test
        public void testGetRouterPortHostInterfaceSuccess() throws Exception {
            Map<UUID, DtoExteriorRouterPort> portMap =
                    new HashMap<UUID, DtoExteriorRouterPort>();

            DtoRouter router1 = topology.getRouter("router1");
            DtoExteriorRouterPort[] routerPorts = dtoResource.getAndVerifyOk(
                    router1.getPorts(),
                    APPLICATION_PORT_COLLECTION_JSON,
                    DtoExteriorRouterPort[].class);

            for (DtoExteriorRouterPort port : routerPorts) {
                portMap.put(port.getId(), port);
            }
            assertThat("router1 should contain the only one port.",
                    portMap.size(), is(1));
            // Update port1 to reflect the host-interface-port binding.
            DtoPort updatedPort1 = dtoResource.getAndVerifyOk(port1.getUri(),
                    APPLICATION_PORT_JSON, DtoExteriorRouterPort.class);
            assertThat("port1 should not be the null value",
                    portMap.get(updatedPort1.getId()), not(nullValue()));
            assertThat("router1 should contain port1",
                    portMap.get(updatedPort1.getId()), is(equalTo(updatedPort1)));

            DtoHostInterfacePort hostInterfacePortFromPort1 =
                    dtoResource.getAndVerifyOk(
                            updatedPort1.getHostInterfacePort(),
                            APPLICATION_HOST_INTERFACE_PORT_JSON,
                            DtoHostInterfacePort.class);
            assertThat("router1 should contain the host-interface-port binding.",
                    hostInterfacePort1, is(notNullValue()));
            DtoHostInterfacePort hostInterfacePort1 =
                    hostTopology.getHostInterfacePort(
                            hostInterfacePortFromPort1.getPortId());
            assertThat("router1 should contain hostInterfacePort1",
                    hostInterfacePortFromPort1,
                    is(equalTo(hostInterfacePort1)));
        }

        /**
         * Test that the bridge's port has the appropriate host-interface-port
         * binding.
         *
         * @throws Exception
         */
        @Test
        public void testGetBridgePortHostInterfaceSuccess() throws Exception {
            Map<UUID, DtoBridgePort> portMap = new HashMap<UUID, DtoBridgePort>();

            DtoBridge bridge1 = topology.getBridge("bridge1");
            DtoBridgePort[] bridgePorts = dtoResource.getAndVerifyOk(
                    bridge1.getPorts(),
                    APPLICATION_PORT_COLLECTION_JSON,
                    DtoBridgePort[].class);

            for (DtoBridgePort port : bridgePorts) {
                portMap.put(port.getId(), port);
            }
            assertThat("bridge1 should contain the only one port.",
                    portMap.size(), is(1));
            // Update port1 to reflect the host-interface-port binding.
            DtoBridgePort updatedPort2 = dtoResource.getAndVerifyOk(port2.getUri(),
                    APPLICATION_PORT_JSON, DtoBridgePort.class);
            assertThat("bridge1 should not be the null value",
                    portMap.get(updatedPort2.getId()), is(notNullValue()));
            assertThat("bridge1 should contain port1",
                    portMap.get(updatedPort2.getId()), is(equalTo(updatedPort2)));
            DtoHostInterfacePort hostInterfacePortFromPort2 =
                    dtoResource.getAndVerifyOk(
                            updatedPort2.getHostInterfacePort(),
                            APPLICATION_HOST_INTERFACE_PORT_JSON,
                            DtoHostInterfacePort.class);
            assertThat("bridge1 should contain the host-interface-port binding.",
                    hostInterfacePort2, is(notNullValue()));
            DtoHostInterfacePort hostInterfacePort2 =
                    hostTopology.getHostInterfacePort(
                            hostInterfacePortFromPort2.getPortId());
            assertThat("router1 should contain hostInterfacePort2",
                    hostInterfacePortFromPort2,
                    is(equalTo(hostInterfacePort2)));
        }

        /**
         * Test that the vlan bridge's port has the appropriate host-interface-port
         * binding.
         *
         * @throws Exception
         */
        @Test
        public void testGetVlanBridgePortHostInterfaceSuccess() throws Exception {
            Map<UUID, DtoVlanBridgeTrunkPort> portMap =
                new HashMap<UUID, DtoVlanBridgeTrunkPort>();

            DtoVlanBridge vlanBr = topology.getVlanBridge("vlan-bridge1");
            DtoVlanBridgeTrunkPort[] trunkPorts = dtoResource.getAndVerifyOk(
                vlanBr.getTrunkPorts(),
                APPLICATION_PORT_COLLECTION_JSON,
                DtoVlanBridgeTrunkPort[].class);

            for (DtoVlanBridgeTrunkPort port : trunkPorts) {
                portMap.put(port.getId(), port);
            }
            assertThat("vlan-bridge1 should contain the only one port.",
                       portMap.size(), is(1));
            // Update trunk port to reflect the host-interface-port binding.
            DtoVlanBridgeTrunkPort updatedPort2 = dtoResource.getAndVerifyOk(
                                    trunkPort1.getUri(), APPLICATION_PORT_JSON,
                                    DtoVlanBridgeTrunkPort.class);
            assertThat("vlan bridge1 should not be the null value",
                       portMap.get(updatedPort2.getId()), is(notNullValue()));
            assertThat("vlan bridge1 should contain port1",
                       portMap.get(updatedPort2.getId()), is(equalTo(updatedPort2)));
            DtoHostInterfacePort hostInterfacePortFromPort2 =
                dtoResource.getAndVerifyOk(
                    updatedPort2.getHostInterfacePort(),
                    APPLICATION_HOST_INTERFACE_PORT_JSON,
                    DtoHostInterfacePort.class);
            assertThat("vlan bridge1 should contain the host-interface-port binding.",
                       hostInterfacePort3, is(notNullValue()));
            DtoHostInterfacePort hostInterfacePort2 =
                hostTopology.getHostInterfacePort(
                    hostInterfacePortFromPort2.getPortId());
            assertThat("router1 should contain hostInterfacePort2",
                       hostInterfacePortFromPort2,
                       is(equalTo(hostInterfacePort2)));
        }

    }

}
