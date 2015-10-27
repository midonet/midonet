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
package org.midonet.cluster.rest_api.network;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.core.Response;

import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import org.midonet.cluster.rest_api.host.rest_api.HostTopology;
import org.midonet.cluster.rest_api.rest_api.DtoWebResource;
import org.midonet.cluster.rest_api.rest_api.FuncTest;
import org.midonet.cluster.rest_api.rest_api.Topology;
import org.midonet.client.MidonetApi;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoBridgePort;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoHost;
import org.midonet.client.dto.DtoHostInterfacePort;
import org.midonet.client.dto.DtoInterface;
import org.midonet.client.dto.DtoLink;
import org.midonet.client.dto.DtoPort;
import org.midonet.client.dto.DtoPortGroup;
import org.midonet.client.dto.DtoPortGroupPort;
import org.midonet.client.dto.DtoRouter;
import org.midonet.client.dto.DtoRouterPort;
import org.midonet.client.dto.DtoRuleChain;
import org.midonet.client.dto.DtoTunnelZone;
import org.midonet.client.dto.DtoTunnelZoneHost;
import org.midonet.packets.MAC;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.*;

@RunWith(Enclosed.class)
public class TestPort {

    public static DtoRouterPort createRouterPort(
        UUID id, UUID deviceId, String networkAddr, int networkLen,
        String portAddr, UUID vifId, UUID inboundFilterId,
        UUID outboundFilterId) {
        DtoRouterPort port = new DtoRouterPort();
        port.setId(id);
        port.setAdminStateUp(true);
        port.setDeviceId(deviceId);
        port.setNetworkAddress(networkAddr);
        port.setNetworkLength(networkLen);
        port.setPortAddress(portAddr);
        port.setVifId(vifId);
        port.setInboundFilterId(inboundFilterId);
        port.setOutboundFilterId(outboundFilterId);

        return port;
    }

    public static DtoBridgePort createBridgePort(
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

    public static DtoRouterPort createRouterPort(UUID id,
            UUID deviceId, String networkAddr, int networkLen,
            String portAddr) {
        DtoRouterPort port = new DtoRouterPort();
        port.setId(id);
        port.setDeviceId(deviceId);
        port.setNetworkAddress(networkAddr);
        port.setNetworkLength(networkLen);
        port.setPortAddress(portAddr);
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

        @Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<>();

            // Bad network address
            DtoRouterPort badNetworkAddr = createRouterPort(null, null,
                    "badAddr", 24, "192.168.100.1");
            params.add(new Object[] { badNetworkAddr, "networkAddress" });

            // Bad port address
            DtoRouterPort badPortAddr = createRouterPort(null, null,
                    "10.0.0.0", 24, "badAddr");
            params.add(new Object[] { badPortAddr, "portAddress" });

            // Bad network len
            DtoRouterPort networkLenTooBig = createRouterPort(null,
                    null, "10.0.0.0", 33, "192.168.100.1");
            params.add(new Object[] { networkLenTooBig, "networkLength" });

            // Negative network len
            DtoRouterPort networkLenNegative = createRouterPort(null,
                    null, "10.0.0.0", -1, "192.168.100.1");
            params.add(new Object[] { networkLenNegative, "networkLength" });

            return params;
        }

        @Test
        public void testBadInputCreate() {

            DtoRouter router = topology.getRouter("router1");

            DtoError error = dtoResource.postAndVerifyBadRequest(
                    router.getPorts(), APPLICATION_PORT_V3_JSON(), port);
            List<Map<String, String>> violations = error.getViolations();
            assertEquals(1, violations.size());
            assertEquals(property, violations.get(0).get("property"));
        }
    }


    public static class TestAllPortCrudSuccess extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;
        private URI portsUri;
        private int portCounter;

        public TestAllPortCrudSuccess() {
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

            DtoRouter r2 = new DtoRouter();
            r2.setName("router2-name");
            r2.setTenantId("tenant1-id");

            // Create a bridge
            DtoBridge b = new DtoBridge();
            b.setName("bridge1-name");
            b.setTenantId("tenant1-id");

            topology = new Topology.Builder(dtoResource)
                    .create("router1", r1)
                    .create("router2", r2)
                    .create("bridge1", b)
                    .build();

            DtoApplication app = topology.getApplication();
            portsUri = app.getPorts();
            portCounter = 0;
        }

        private void verifyPortNumber(int num) {
            DtoPort[] ports = dtoResource.getAndVerifyOk(portsUri,
                    APPLICATION_PORT_V3_COLLECTION_JSON(),
                    DtoPort[].class);
            assertEquals(num, ports.length);
        }

        @Test
        public void testCrudOnTheSameBridge() {
            DtoBridge b = topology.getBridge("bridge1");
            verifyPortNumber(portCounter);

            // Create an exterior bridge port.
            DtoBridgePort bridgePort1 = new DtoBridgePort();
            bridgePort1.setDeviceId(b.getId());
            bridgePort1 = dtoResource.postAndVerifyCreated(b.getPorts(),
                    APPLICATION_PORT_V3_JSON(), bridgePort1, DtoBridgePort.class);
            portCounter++;
            verifyPortNumber(portCounter);

            // Create another exterior bridge port.
            DtoBridgePort bridgePort2 = new DtoBridgePort();
            bridgePort2.setDeviceId(b.getId());
            bridgePort2 = dtoResource.postAndVerifyCreated(b.getPorts(),
                    APPLICATION_PORT_V3_JSON(), bridgePort2, DtoBridgePort.class);
            portCounter++;
            verifyPortNumber(portCounter);

            // Delete the first bridge port
            dtoResource.deleteAndVerifyNoContent(bridgePort1.getUri(),
                    APPLICATION_PORT_V3_JSON());
            portCounter--;
            verifyPortNumber(portCounter);

            // Delete the second bridge port
            dtoResource.deleteAndVerifyNoContent(bridgePort2.getUri(),
                    APPLICATION_PORT_V3_JSON());
            portCounter--;
            verifyPortNumber(portCounter);
        }

        @Test
        public void testCrudOnTheSameRouter() {
            DtoRouter r = topology.getRouter("router1");
            verifyPortNumber(portCounter);

            // Create a router port.
            DtoRouterPort routerPort1 = createRouterPort(null, r.getId(),
                    "10.0.0.0", 24, "10.0.0.1");
            routerPort1 = dtoResource.postAndVerifyCreated(r.getPorts(),
                    APPLICATION_PORT_V3_JSON(), routerPort1, DtoRouterPort.class);
            portCounter++;
            verifyPortNumber(portCounter);

            // Create another router port.
            DtoRouterPort routerPort2 = createRouterPort(null, r.getId(),
                    "10.0.0.0", 24, "10.0.0.2");
            routerPort2 = dtoResource.postAndVerifyCreated(r.getPorts(),
                    APPLICATION_PORT_V3_JSON(), routerPort2, DtoRouterPort.class);
            portCounter++;
            verifyPortNumber(portCounter);

            // Delete the first router port.
            dtoResource.deleteAndVerifyNoContent(routerPort1.getUri(),
                    APPLICATION_PORT_V3_JSON());
            portCounter--;
            verifyPortNumber(portCounter);

            // Delete the second router port.
            dtoResource.deleteAndVerifyNoContent(routerPort2.getUri(),
                    APPLICATION_PORT_V3_JSON());
            portCounter--;
            verifyPortNumber(portCounter);
        }

        @Test
        public void testCrudOnTheDifferentDevices() {
            DtoRouter r = topology.getRouter("router1");
            DtoBridge b = topology.getBridge("bridge1");
            verifyPortNumber(portCounter);

            // Create an exterior bridge port.
            DtoBridgePort bridgePort = new DtoBridgePort();
            bridgePort.setDeviceId(b.getId());
            bridgePort = dtoResource.postAndVerifyCreated(b.getPorts(),
                    APPLICATION_PORT_V3_JSON(), bridgePort, DtoBridgePort.class);
            portCounter++;
            verifyPortNumber(portCounter);

            // Create a router port.
            DtoRouterPort routerPort = createRouterPort(null, r.getId(),
                    "10.0.0.0", 24, "10.0.0.1");
            routerPort = dtoResource.postAndVerifyCreated(r.getPorts(),
                    APPLICATION_PORT_V3_JSON(), routerPort, DtoRouterPort.class);
            portCounter++;
            verifyPortNumber(portCounter);

            // Delete the bridge port.
            dtoResource.deleteAndVerifyNoContent(bridgePort.getUri(),
                    APPLICATION_PORT_V3_JSON());
            portCounter--;
            verifyPortNumber(portCounter);

            // Delete the router port.
            dtoResource.deleteAndVerifyNoContent(routerPort.getUri(),
                    APPLICATION_PORT_V3_JSON());
            portCounter--;
            verifyPortNumber(portCounter);
        }

        @Test
        public void testCrudOfDuplicatedPortsOnTheDifferentDevices() {
            DtoRouter r1 = topology.getRouter("router1");
            DtoRouter r2 = topology.getRouter("router2");
            DtoBridge b = topology.getBridge("bridge1");
            verifyPortNumber(portCounter);

            // Create an exterior bridge port.
            DtoBridgePort bridgePort = new DtoBridgePort();
            bridgePort.setDeviceId(b.getId());
            bridgePort = dtoResource.postAndVerifyCreated(b.getPorts(),
                    APPLICATION_PORT_V3_JSON(), bridgePort, DtoBridgePort.class);
            portCounter++;
            verifyPortNumber(portCounter);

            // Create a router port.
            DtoRouterPort routerPort1 = createRouterPort(null, r1.getId(),
                    "10.0.0.0", 24, "10.0.0.1");
            routerPort1 = dtoResource.postAndVerifyCreated(r1.getPorts(),
                    APPLICATION_PORT_V3_JSON(), routerPort1, DtoRouterPort.class);
            portCounter++;
            verifyPortNumber(portCounter);

            // Create another router port.
            DtoRouterPort routerPort2 = createRouterPort(null, r1.getId(),
                    "10.0.0.0", 24, "10.0.0.1");
            routerPort2 = dtoResource.postAndVerifyCreated(r2.getPorts(),
                    APPLICATION_PORT_V3_JSON(), routerPort2, DtoRouterPort.class);
            portCounter++;
            verifyPortNumber(portCounter);

            // Delete the bridge port.
            dtoResource.deleteAndVerifyNoContent(bridgePort.getUri(),
                    APPLICATION_PORT_V3_JSON());
            portCounter--;
            verifyPortNumber(portCounter);

            // Delete the first router port.
            dtoResource.deleteAndVerifyNoContent(routerPort1.getUri(),
                    APPLICATION_PORT_V3_JSON());
            portCounter--;
            verifyPortNumber(portCounter);

            // Delete the second router port.
            dtoResource.deleteAndVerifyNoContent(routerPort2.getUri(),
                    APPLICATION_PORT_V3_JSON());
            portCounter--;
            verifyPortNumber(portCounter);
        }

        @Test
        public void testCreatePortOnBridgeWithoutDeviceId() throws Exception {
            DtoBridge bridge1 = topology.getBridge("bridge1");
            URI uri = bridge1.getPorts();
            uri = new URI(uri.toString().replace(bridge1.getId().toString(),
                    UUID.randomUUID().toString()));
            DtoBridgePort port = createBridgePort(null, null, null, null, null);
            dtoResource.postAndVerifyStatus(uri,
                    APPLICATION_PORT_V3_JSON(), port, 404);
        }

        @Test
        public void testCreatePortOnRouterWithoutDeviceId() throws Exception {
            DtoRouter router1 = topology.getRouter("router1");
            URI uri = router1.getPorts();
            uri = new URI(uri.toString().replace(router1.getId().toString(),
                    UUID.randomUUID().toString()));
            DtoRouterPort port = createRouterPort(null,
                    router1.getId(),
                    "10.0.10.0",
                    24,
                    "10.0.10.1");
            dtoResource.postAndVerifyStatus(uri,
                    APPLICATION_PORT_V3_JSON(), port, 404);
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

            // Create a bridge
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

        @Test
        public void testCrudBridgePortSameVLAN() {

            // Get the bridge and chains
            DtoBridge b = topology.getBridge("bridge1");
            topology.getChain("chain1");
            topology.getChain("chain2");

            // Create an Interior bridge port
            DtoBridgePort b1Lp1 = new DtoBridgePort();
            short vlanId = 2727;
            b1Lp1.setDeviceId(b.getId());
            b1Lp1.setVlanId(vlanId);
            b1Lp1 = dtoResource.postAndVerifyCreated(b.getPorts(),
                    APPLICATION_PORT_V3_JSON(), b1Lp1, DtoBridgePort.class);

            // This test can't be used because MockDirectory.multi doesn't handle failed ops

            // Create another Interior bridge port with same VLAN, should fail
            //DtoBridgePort b1Lp2 = new DtoBridgePort();
            //b1Lp2.setDeviceId(b.getId());
            //b1Lp2.setVlanId(vlanId);
            //dtoResource.postAndVerifyBadRequest(b.getPorts(),
            //        APPLICATION_PORT_V3_JSON(), b1Lp2);

            // Delete the initial port
            dtoResource.deleteAndVerifyNoContent(b1Lp1.getUri(),
                    APPLICATION_PORT_V3_JSON());

            // Should now be able to add a port with same VLAN
            DtoBridgePort b1Lp2 = new DtoBridgePort();
            b1Lp2.setDeviceId(b.getId());
            b1Lp2.setVlanId(vlanId);
            dtoResource.postAndVerifyCreated(b.getPorts(),
                                             APPLICATION_PORT_V3_JSON(), b1Lp2,
                                             DtoBridgePort.class);

            // Try delete the bridge, test it deletes cleanly
            dtoResource.deleteAndVerifyNoContent(b.getUri(),
                                                 APPLICATION_BRIDGE_JSON_V4());
        }

        @Test
        public void testCrudBridgePort() {
            // Get the bridge and chains
            DtoBridge b = topology.getBridge("bridge1");
            DtoRuleChain c1 = topology.getChain("chain1");
            DtoRuleChain c2 = topology.getChain("chain2");

            DtoBridgePort b1Lp1 = new DtoBridgePort();
            short vlanId = 666;
            b1Lp1.setDeviceId(b.getId());
            b1Lp1.setVlanId(vlanId);
            b1Lp1.setAdminStateUp(false);
            b1Lp1 = dtoResource.postAndVerifyCreated(b.getPorts(),
                APPLICATION_PORT_V3_JSON(), b1Lp1, DtoBridgePort.class);

            //Change admin state up
            b1Lp1.setAdminStateUp(true);
            b1Lp1 = dtoResource.putAndVerifyNoContent(b1Lp1.getUri(),
                    APPLICATION_PORT_V3_JSON(), b1Lp1, DtoBridgePort.class);
            assertTrue(b1Lp1.isAdminStateUp());

            //Create exterior bridge port
            DtoBridgePort b1Mp1 = new DtoBridgePort();
            b1Mp1.setDeviceId(b.getId());
            b1Mp1.setInboundFilterId(c1.getId());
            b1Mp1.setOutboundFilterId(c2.getId());
            b1Mp1 = dtoResource.postAndVerifyCreated(b.getPorts(),
                APPLICATION_PORT_V3_JSON(), b1Mp1, DtoBridgePort.class);

            // List ports
            DtoBridgePort[] ports = dtoResource.getAndVerifyOk(
                b.getPorts(), APPLICATION_PORT_V3_COLLECTION_JSON(),
                DtoBridgePort[].class);
            assertEquals(2, ports.length);

            // Update VIFs
            assertNull(b1Mp1.getVifId());
            UUID vifId = UUID.randomUUID();
            b1Mp1.setVifId(vifId);
            b1Mp1 = dtoResource.putAndVerifyNoContent(b1Mp1.getUri(),
                APPLICATION_PORT_V3_JSON(), b1Mp1, DtoBridgePort.class);
            assertEquals(vifId, b1Mp1.getVifId());

            b1Mp1.setVifId(null);
            b1Mp1 = dtoResource.putAndVerifyNoContent(b1Mp1.getUri(),
                APPLICATION_PORT_V3_JSON(), b1Mp1, DtoBridgePort.class);
            assertNull(b1Mp1.getVifId());

            // Update chains
            assertNotNull(b1Mp1.getInboundFilterId());
            assertNotNull(b1Mp1.getOutboundFilterId());
            b1Mp1.setInboundFilterId(null);
            b1Mp1.setOutboundFilterId(null);
            b1Mp1 = dtoResource.putAndVerifyNoContent(b1Mp1.getUri(),
                APPLICATION_PORT_V3_JSON(), b1Mp1, DtoBridgePort.class);
            assertNull(b1Mp1.getInboundFilterId());
            assertNull(b1Mp1.getOutboundFilterId());

            b1Mp1.setInboundFilterId(c1.getId());
            b1Mp1.setOutboundFilterId(c2.getId());
            b1Mp1 = dtoResource.putAndVerifyNoContent(b1Mp1.getUri(),
                APPLICATION_PORT_V3_JSON(), b1Mp1, DtoBridgePort.class);
            assertEquals(c1.getId(), b1Mp1.getInboundFilterId());
            assertEquals(c2.getId(), b1Mp1.getOutboundFilterId());

            // Swap
            b1Mp1.setInboundFilterId(c2.getId());
            b1Mp1.setOutboundFilterId(c1.getId());
            b1Mp1 = dtoResource.putAndVerifyNoContent(b1Mp1.getUri(),
                APPLICATION_PORT_V3_JSON(), b1Mp1, DtoBridgePort.class);
            assertEquals(c2.getId(), b1Mp1.getInboundFilterId());
            assertEquals(c1.getId(), b1Mp1.getOutboundFilterId());

            // Delete the Interior port.
            dtoResource.deleteAndVerifyNoContent(b1Lp1.getUri(),
                APPLICATION_PORT_V3_JSON());

            // Make sure it's no longer there
            dtoResource.getAndVerifyNotFound(b1Lp1.getUri(),
                APPLICATION_PORT_V3_JSON());

            // Delete the mat port.
            dtoResource.deleteAndVerifyNoContent(b1Mp1.getUri(),
                APPLICATION_PORT_V3_JSON());

            // Make sure it's no longer there
            dtoResource.getAndVerifyNotFound(b1Mp1.getUri(),
                APPLICATION_PORT_V3_JSON());

            // List and make sure not port found
            ports = dtoResource.getAndVerifyOk(b.getPorts(),
                APPLICATION_PORT_V3_COLLECTION_JSON(),
                DtoBridgePort[].class);
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

        @Test
        public void testCrudRouterPort() {
            // Get the router and chains
            DtoRouter r = topology.getRouter("router1");
            DtoRuleChain c1 = topology.getChain("chain1");
            DtoRuleChain c2 = topology.getChain("chain2");

            // Create a Interior router port
            DtoRouterPort r1Lp1 = createRouterPort(null,
                r.getId(),
                "10.0.0.0",
                24,
                "10.0.0.1");
            r1Lp1.setAdminStateUp(false);
            r1Lp1 = dtoResource.postAndVerifyCreated(r.getPorts(),
                APPLICATION_PORT_V3_JSON(), r1Lp1, DtoRouterPort.class);

            //Change admin state up
            r1Lp1.setAdminStateUp(true);
            r1Lp1 = dtoResource.putAndVerifyNoContent(r1Lp1.getUri(),
                    APPLICATION_PORT_V3_JSON(), r1Lp1, DtoRouterPort.class);
            assertTrue(r1Lp1.isAdminStateUp());

            // Create a Exterior router port
            UUID vifId = UUID.randomUUID();
            DtoRouterPort r1Mp1 = createRouterPort(
                null, r.getId(), "10.0.0.0", 24, "10.0.0.1",
                vifId, c1.getId(), c2.getId());
            r1Mp1 = dtoResource.postAndVerifyCreated(r.getPorts(),
                APPLICATION_PORT_V3_JSON(), r1Mp1,
                DtoRouterPort.class);
            assertEquals(vifId, r1Mp1.getVifId());

            // List ports
            DtoRouterPort[] ports = dtoResource.getAndVerifyOk(r.getPorts(),
                APPLICATION_PORT_V3_COLLECTION_JSON(), DtoRouterPort[].class);
            assertEquals(2, ports.length);

            // Update VIFs
            vifId = UUID.randomUUID();
            r1Mp1.setVifId(vifId);
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                APPLICATION_PORT_V3_JSON(), r1Mp1,
                DtoRouterPort.class);
            assertEquals(vifId, r1Mp1.getVifId());

            r1Mp1.setVifId(null);
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                APPLICATION_PORT_V3_JSON(), r1Mp1,
                DtoRouterPort.class);
            assertNull(r1Mp1.getVifId());

            // Update chains
            assertNotNull(r1Mp1.getInboundFilterId());
            assertNotNull(r1Mp1.getOutboundFilterId());
            r1Mp1.setInboundFilterId(null);
            r1Mp1.setOutboundFilterId(null);
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                APPLICATION_PORT_V3_JSON(),
                r1Mp1,
                DtoRouterPort.class);
            assertNull(r1Mp1.getInboundFilterId());
            assertNull(r1Mp1.getOutboundFilterId());

            r1Mp1.setInboundFilterId(c1.getId());
            r1Mp1.setOutboundFilterId(c2.getId());
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                APPLICATION_PORT_V3_JSON(), r1Mp1,
                DtoRouterPort.class);
            assertEquals(c1.getId(), r1Mp1.getInboundFilterId());
            assertEquals(c2.getId(), r1Mp1.getOutboundFilterId());

            // Swap
            r1Mp1.setInboundFilterId(c2.getId());
            r1Mp1.setOutboundFilterId(c1.getId());
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                APPLICATION_PORT_V3_JSON(), r1Mp1,
                DtoRouterPort.class);
            assertEquals(c2.getId(), r1Mp1.getInboundFilterId());
            assertEquals(c1.getId(), r1Mp1.getOutboundFilterId());

            // Delete the Interior port.
            dtoResource.deleteAndVerifyNoContent(r1Lp1.getUri(),
                APPLICATION_PORT_V3_JSON());

            // Make sure it's no longer there
            dtoResource.getAndVerifyNotFound(r1Lp1.getUri(),
                APPLICATION_PORT_V3_JSON());

            // Delete the mat port.
            dtoResource.deleteAndVerifyNoContent(r1Mp1.getUri(),
                APPLICATION_PORT_V3_JSON());

            // Make sure it's no longer there
            dtoResource.getAndVerifyNotFound(r1Mp1.getUri(),
                APPLICATION_PORT_V3_JSON());

            // List and make sure not port found
            ports = dtoResource.getAndVerifyOk(r.getPorts(),
                APPLICATION_PORT_V3_COLLECTION_JSON(), DtoRouterPort[].class);
            assertEquals(0, ports.length);
        }

        @Test
        public void testRouterPortHasMACWithMidokuraOUI() {
            // Get the router and chains
            DtoRouter router = topology.getRouter("router1");
            // Create a Interior router port
            DtoRouterPort routerPort = createRouterPort(null,
                    router.getId(),
                    "10.0.0.0",
                    24,
                    "10.0.0.1");
            routerPort.setAdminStateUp(false);
            routerPort = dtoResource.postAndVerifyCreated(
                    router.getPorts(),
                    APPLICATION_PORT_V3_JSON(),
                    routerPort,
                    DtoRouterPort.class);
            MAC routerPortMac =
                    MAC.fromString(routerPort.getPortMac());
            assertEquals(MAC.MIDOKURA_OUI_MASK,
                    routerPortMac.asLong() & MAC.MIDOKURA_OUI_MASK);
        }

        @Test
        @Deprecated
        public void testCrudDeprecatedV1RouterPort() {

            // Get the router and chains
            DtoRouter r = topology.getRouter("router1");
            DtoRuleChain c1 = topology.getChain("chain1");
            DtoRuleChain c2 = topology.getChain("chain2");

            // Create a Interior router port
            DtoRouterPort r1Lp1 = createRouterPort(null, r.getId(), "10.0.0.0",
                    24, "10.0.0.1");
            r1Lp1 = dtoResource.postAndVerifyCreated(r.getPorts(),
                    APPLICATION_PORT_V3_JSON(), r1Lp1, DtoRouterPort.class);

            // Create a Exterior router port
            UUID vifId = UUID.randomUUID();
            DtoRouterPort r1Mp1 = createRouterPort(
                    null, r.getId(), "10.0.0.0", 24, "10.0.0.1",
                    vifId, c1.getId(), c2.getId());
            r1Mp1 = dtoResource.postAndVerifyCreated(r.getPorts(),
                    APPLICATION_PORT_V3_JSON(), r1Mp1,
                    DtoRouterPort.class);
            assertEquals(vifId, r1Mp1.getVifId());

            // List ports
            DtoRouterPort[] ports = dtoResource.getAndVerifyOk(r.getPorts(),
                    APPLICATION_PORT_V3_COLLECTION_JSON(), DtoRouterPort[].class);
            assertEquals(2, ports.length);

            // Update VIFs
            vifId = UUID.randomUUID();
            r1Mp1.setVifId(vifId);
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                    APPLICATION_PORT_V3_JSON(), r1Mp1,
                    DtoRouterPort.class);
            assertEquals(vifId, r1Mp1.getVifId());

            r1Mp1.setVifId(null);
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                    APPLICATION_PORT_V3_JSON(), r1Mp1,
                    DtoRouterPort.class);
            assertNull(r1Mp1.getVifId());

            // Update chains
            assertNotNull(r1Mp1.getInboundFilterId());
            assertNotNull(r1Mp1.getOutboundFilterId());
            r1Mp1.setInboundFilterId(null);
            r1Mp1.setOutboundFilterId(null);
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                                                      APPLICATION_PORT_V3_JSON(),
                                                      r1Mp1,
                                                      DtoRouterPort.class);
            assertNull(r1Mp1.getInboundFilterId());
            assertNull(r1Mp1.getOutboundFilterId());

            r1Mp1.setInboundFilterId(c1.getId());
            r1Mp1.setOutboundFilterId(c2.getId());
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                    APPLICATION_PORT_V3_JSON(), r1Mp1,
                    DtoRouterPort.class);
            assertEquals(c1.getId(), r1Mp1.getInboundFilterId());
            assertEquals(c2.getId(), r1Mp1.getOutboundFilterId());

            // Swap
            r1Mp1.setInboundFilterId(c2.getId());
            r1Mp1.setOutboundFilterId(c1.getId());
            r1Mp1 = dtoResource.putAndVerifyNoContent(r1Mp1.getUri(),
                    APPLICATION_PORT_V3_JSON(), r1Mp1,
                    DtoRouterPort.class);
            assertEquals(c2.getId(), r1Mp1.getInboundFilterId());
            assertEquals(c1.getId(), r1Mp1.getOutboundFilterId());

            // Delete the Interior port.
            dtoResource.deleteAndVerifyNoContent(r1Lp1.getUri(),
                                                 APPLICATION_PORT_V3_JSON());

            // Make sure it's no longer there
            dtoResource.getAndVerifyNotFound(r1Lp1.getUri(),
                                             APPLICATION_PORT_V3_JSON());

            // Delete the mat port.
            dtoResource.deleteAndVerifyNoContent(r1Mp1.getUri(),
                                                 APPLICATION_PORT_V3_JSON());

            // Make sure it's no longer there
            dtoResource.getAndVerifyNotFound(r1Mp1.getUri(),
                                             APPLICATION_PORT_V3_JSON());

            // List and make sure not port found
            ports = dtoResource.getAndVerifyOk(r.getPorts(),
                    APPLICATION_PORT_V3_COLLECTION_JSON(), DtoRouterPort[].class);
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
            DtoRouterPort r1Lp1 = createRouterPort(null, null,
                "10.0.0.0", 24, "10.0.0.1");

            // Create another Interior router1 port
            DtoRouterPort r1Lp2 = createRouterPort(null, null,
                "192.168.0.0", 24, "192.168.0.1");

            // Create a Interior router2 port
            DtoRouterPort r2Lp1 = createRouterPort(null, null,
                "10.0.1.0", 24, "10.0.1.1");

            // Create another Interior router2 port
            DtoRouterPort r2Lp2 = createRouterPort(null, null,
                "192.168.1.0",
                24,
                "192.168.1.1");

            topology = new Topology.Builder(dtoResource)
                .create("router1", r1)
                .create("router2", r2)
                .create("bridge1", b1)
                .create("router1", "router1Port1", r1Lp1)
                .create("router1", "router1Port2", r1Lp2)
                .create("router2", "router2Port1", r2Lp1)
                .create("router2", "router2Port2", r2Lp2)
                .create("bridge1", "bridge1Port1",
                    new DtoBridgePort())
                .create("bridge1", "bridge1Port2",
                    new DtoBridgePort())
                .build();
        }

        @Test
        public void testLinkUnlink() {
            DtoRouter router1 = topology.getRouter("router1");
            DtoRouter router2 = topology.getRouter("router2");
            DtoBridge bridge1 = topology.getBridge("bridge1");
            DtoRouterPort r1p1 = topology
                .getRouterPort("router1Port1");
            DtoRouterPort r1p2 = topology
                .getRouterPort("router1Port2");
            DtoRouterPort r2p1 = topology
                .getRouterPort("router2Port1");
            DtoRouterPort r2p2 = topology
                .getRouterPort("router2Port2");
            DtoBridgePort b1p1 = topology
                .getBridgePort("bridge1Port1");
            DtoBridgePort b1p2 = topology
                .getBridgePort("bridge1Port2");

            // Link router1 and router2
            DtoLink link = new DtoLink();
            link.setPeerId(r2p1.getId());
            dtoResource
                .postAndVerifyStatus(r1p1.getLink(),
                    APPLICATION_PORT_LINK_JSON(), link,
                    Response.Status.CREATED.getStatusCode());

            // Link router1 and bridge1
            link = new DtoLink();
            link.setPeerId(b1p1.getId());
            dtoResource
                .postAndVerifyStatus(r1p2.getLink(),
                    APPLICATION_PORT_LINK_JSON(), link,
                    Response.Status.CREATED.getStatusCode());

            // Link bridge1 and router2
            link = new DtoLink();
            link.setPeerId(r2p2.getId());
            dtoResource
                .postAndVerifyStatus(b1p2.getLink(),
                    APPLICATION_PORT_LINK_JSON(), link,
                    Response.Status.CREATED.getStatusCode());

            // Get the peers
            DtoPort[] ports = dtoResource.getAndVerifyOk(
                router1.getPeerPorts(), APPLICATION_PORT_V3_COLLECTION_JSON(),
                DtoPort[].class);
            assertNotNull(ports);
            assertEquals(2, ports.length);

            // Get the peers of router2
            ports = dtoResource.getAndVerifyOk(router2.getPeerPorts(),
                APPLICATION_PORT_V3_COLLECTION_JSON(), DtoPort[].class);
            assertNotNull(ports);
            assertEquals(2, ports.length);

            // Get the peers of bridge1
            ports = dtoResource.getAndVerifyOk(bridge1.getPeerPorts(),
                APPLICATION_PORT_V3_COLLECTION_JSON(), DtoPort[].class);
            assertNotNull(ports);
            assertEquals(2, ports.length);

            // Cannot link already linked ports
            link = new DtoLink();
            link.setPeerId(r2p1.getId());
            dtoResource.postAndVerifyBadRequest(r1p1.getLink(),
                APPLICATION_PORT_LINK_JSON(), link);
            link = new DtoLink();
            link.setPeerId(r2p1.getId());
            dtoResource.postAndVerifyBadRequest(b1p2.getLink(),
                APPLICATION_PORT_LINK_JSON(), link);

            // Unlink
            dtoResource.deleteAndVerifyStatus(r1p1.getLink(),
                APPLICATION_PORT_LINK_JSON(),
                Response.Status.NO_CONTENT.getStatusCode());
            dtoResource.deleteAndVerifyStatus(r1p2.getLink(),
                APPLICATION_PORT_LINK_JSON(),
                Response.Status.NO_CONTENT.getStatusCode());
            dtoResource.deleteAndVerifyStatus(b1p1.getLink(),
                APPLICATION_PORT_LINK_JSON(),
                Response.Status.NO_CONTENT.getStatusCode());
            dtoResource.deleteAndVerifyStatus(b1p2.getLink(),
                APPLICATION_PORT_LINK_JSON(),
                Response.Status.NO_CONTENT.getStatusCode());
            dtoResource.deleteAndVerifyStatus(r2p1.getLink(),
                APPLICATION_PORT_LINK_JSON(),
                Response.Status.NO_CONTENT.getStatusCode());
            dtoResource.deleteAndVerifyStatus(r2p2.getLink(),
                APPLICATION_PORT_LINK_JSON(),
                Response.Status.NO_CONTENT.getStatusCode());

            // Delete all the ports
            dtoResource.deleteAndVerifyNoContent(r1p1.getUri(),
                APPLICATION_PORT_V3_JSON());
            dtoResource.deleteAndVerifyNoContent(r1p2.getUri(),
                APPLICATION_PORT_V3_JSON());
            dtoResource.deleteAndVerifyNoContent(r2p1.getUri(),
                APPLICATION_PORT_V3_JSON());
            dtoResource.deleteAndVerifyNoContent(r2p2.getUri(),
                APPLICATION_PORT_V3_JSON());
            dtoResource.deleteAndVerifyNoContent(b1p1.getUri(),
                APPLICATION_PORT_V3_JSON());
            dtoResource.deleteAndVerifyNoContent(b1p2.getUri(),
                APPLICATION_PORT_V3_JSON());
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
            DtoBridgePort port1 = createBridgePort(null, null,
                    null, null, null);

            topology = new Topology.Builder(dtoResource)
                    .create("bridge1", b1)
                    .create("bridge1", "port1", port1).build();
        }

        @Test
        public void testUpdate() throws Exception {

            DtoBridgePort origPort = topology.getBridgePort("port1");

            assertNull(origPort.getVifId());

            origPort.setVifId(UUID.randomUUID());
            DtoBridgePort newPort = dtoResource.putAndVerifyNoContent(
                    origPort.getUri(),
                    APPLICATION_PORT_V3_JSON(), origPort,
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
            DtoBridgePort bridgePort = createBridgePort(null,
                    null, null, null, null);

            topology = new Topology.Builder(dtoResource)
                    .create("pg1", pg1)
                    .create("bg1", bg1)
                    .create("bg1", "port1", bridgePort).build();
        }

        @Test
        public void testCrudSuccess() throws Exception {

            DtoPortGroup pg1 = topology.getPortGroup("pg1");
            DtoBridgePort port1 = topology.getBridgePort("port1");

            // List and make sure there is no membership
            DtoPortGroupPort[] portGroupPorts = dtoResource.getAndVerifyOk(
                    pg1.getPorts(),
                    APPLICATION_PORTGROUP_PORT_COLLECTION_JSON(),
                    DtoPortGroupPort[].class);
            assertEquals(0, portGroupPorts.length);

            // Add a port to a group
            DtoPortGroupPort portGroupPort = new DtoPortGroupPort();
            portGroupPort.setPortGroupId(pg1.getId());
            portGroupPort.setPortId(port1.getId());
            portGroupPort = dtoResource.postAndVerifyCreated(pg1.getPorts(),
                    APPLICATION_PORTGROUP_PORT_JSON(),
                    portGroupPort, DtoPortGroupPort.class);

            // List all.  There should be one now
            portGroupPorts = dtoResource.getAndVerifyOk(
                    pg1.getPorts(),
                    APPLICATION_PORTGROUP_PORT_COLLECTION_JSON(),
                    DtoPortGroupPort[].class);
            assertEquals(1, portGroupPorts.length);

            // List from port's URI
            DtoPortGroupPort[] portGroups = dtoResource.getAndVerifyOk(
                    port1.getPortGroups(),
                    APPLICATION_PORTGROUP_PORT_COLLECTION_JSON(),
                    DtoPortGroupPort[].class);
            assertEquals(1, portGroups.length);

            // Delete the membership
            dtoResource.deleteAndVerifyNoContent(portGroupPort.getUri(),
                    APPLICATION_PORTGROUP_PORT_JSON());

            // List once again, and make sure it's not there
            portGroupPorts = dtoResource.getAndVerifyOk(
                    pg1.getPorts(),
                    APPLICATION_PORTGROUP_PORT_COLLECTION_JSON(),
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
        private MidonetApi api;

        private DtoRouter router1;
        private DtoBridge bridge1;
        private DtoRouterPort port1;
        private DtoBridgePort port2;
        private DtoHost host1, host2;
        private DtoInterface interface1, interface2;
        private DtoHostInterfacePort hostInterfacePort1, hostInterfacePort2;

        /**
         * Constructor to initialize the test cases with the configuration.
         */
        public TestPortHostInterfaceGetSuccess() {
            super(FuncTest.appDesc);
        }

        /**
         * Set up the logical network topology and the host topology.
         *
         * @throws Exception
         */
        @Before
        @Override
        public void setUp() throws Exception {
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Creating the topology for the exterior **router** port and the
            // interface.
            // Create a router.
            router1 = new DtoRouter();
            router1.setName("router1-name");
            router1.setTenantId("tenant1-id");
            // Create an exterior router port on the router.
            port1 = createRouterPort(
                    UUID.randomUUID(), router1.getId(), "10.0.0.0", 24,
                    "10.0.0.1", null, null, null);
            // Creating the topology for the exterior **bridge** port and the
            // interface.
            // Create a bridge.
            bridge1 = new DtoBridge();
            bridge1.setName("bridge1");
            bridge1.setTenantId("bridge1-name");
            // Create an exterior bridge port on the bridge.
            port2 = createBridgePort(UUID.randomUUID(),
                    bridge1.getId(), null, null, null);
            topology = new Topology.Builder(dtoResource)
                    .create("router1", router1)
                    .create("router1", "port1", port1)
                    .create("bridge1", bridge1)
                    .create("bridge1", "port2", port2)
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
            createHost(UUID.randomUUID(), "host3", true, null);
            port1 = topology.getRouterPort("port1");
            port2 = topology.getBridgePort("port2");

            DtoTunnelZone tunnelZone = new DtoTunnelZone();
            tunnelZone.setName("tz1-name");

            DtoTunnelZoneHost tzh1 = new DtoTunnelZoneHost();
            tzh1.setHostId(host1.getId());
            tzh1.setIpAddress("192.168.100.1");
            tzh1.setTunnelZoneId(tunnelZone.getId());

            DtoTunnelZoneHost tzh2 = new DtoTunnelZoneHost();
            tzh2.setHostId(host2.getId());
            tzh2.setIpAddress("192.168.100.2");
            tzh2.setTunnelZoneId(tunnelZone.getId());

            // Create a host-interface-port binding finally.
            hostInterfacePort1 = createHostInterfacePort(
                    host1.getId(), interface1.getName(), port1.getId());
            // Create a host-interface-port binding finally.
            hostInterfacePort2 = createHostInterfacePort(
                    host2.getId(), interface2.getName(), port2.getId());
            hostTopology = new HostTopology.Builder(dtoResource)
                    .create("tz1", tunnelZone)
                    .create(host1.getId(), host1)
                    .create("tz1", tzh1.getHostId(), tzh1)
                    .create(hostInterfacePort1.getHostId(),
                            hostInterfacePort1.getPortId(), hostInterfacePort1)
                    .create(host2.getId(), host2)
                    .create("tz1", tzh2.getHostId(), tzh2)
                    .create(hostInterfacePort2.getHostId(),
                            hostInterfacePort2.getPortId(), hostInterfacePort2)
                    .build();

            URI baseUri = resource().getURI();
            api = new MidonetApi(baseUri.toString());
            api.enableLogging();
        }

        /**
         * Test that the router's port has the appropriate host-interface-port
         * binding.
         *
         * @throws Exception
         */
        @Test
        public void testGetRouterPortHostInterfaceSuccess() throws Exception {
            Map<UUID, DtoRouterPort> portMap =
                    new HashMap<>();

            DtoRouter router1 = topology.getRouter("router1");
            DtoRouterPort[] routerPorts = dtoResource.getAndVerifyOk(
                    router1.getPorts(),
                    APPLICATION_PORT_V3_COLLECTION_JSON(),
                    DtoRouterPort[].class);

            for (DtoRouterPort port : routerPorts) {
                portMap.put(port.getId(), port);
            }
            assertThat("router1 should contain the only one port.",
                    portMap.size(), is(1));
            // Update port1 to reflect the host-interface-port binding.
            DtoPort updatedPort1 = dtoResource.getAndVerifyOk(port1.getUri(),
                    APPLICATION_PORT_V3_JSON(), DtoRouterPort.class);
            assertThat("port1 should not be the null value",
                    portMap.get(updatedPort1.getId()), not(nullValue()));
            assertThat("router1 should contain port1",
                    portMap.get(updatedPort1.getId()),
                    is(equalTo(updatedPort1)));

            DtoHostInterfacePort hostInterfacePortFromPort1 =
                    dtoResource.getAndVerifyOk(
                            updatedPort1.getHostInterfacePort(),
                            APPLICATION_HOST_INTERFACE_PORT_JSON(),
                            DtoHostInterfacePort.class);
            assertThat(
                    "router1 should contain the host-interface-port binding.",
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
            Map<UUID, DtoBridgePort> portMap =
                    new HashMap<>();

            DtoBridge bridge1 = topology.getBridge("bridge1");
            DtoBridgePort[] bridgePorts = dtoResource.getAndVerifyOk(
                    bridge1.getPorts(),
                    APPLICATION_PORT_V3_COLLECTION_JSON(),
                    DtoBridgePort[].class);

            for (DtoBridgePort port : bridgePorts) {
                portMap.put(port.getId(), port);
            }
            assertThat("bridge1 should contain the only one port.",
                    portMap.size(), is(1));
            // Update port1 to reflect the host-interface-port binding.
            DtoBridgePort updatedPort2 = dtoResource.getAndVerifyOk(
                    port2.getUri(),
                    APPLICATION_PORT_V3_JSON(), DtoBridgePort.class);
            assertThat("bridge1 should not be the null value",
                    portMap.get(updatedPort2.getId()), is(notNullValue()));
            assertThat("bridge1 should contain port1",
                    portMap.get(updatedPort2.getId()),
                    is(equalTo(updatedPort2)));
            DtoHostInterfacePort hostInterfacePortFromPort2 =
                    dtoResource.getAndVerifyOk(
                            updatedPort2.getHostInterfacePort(),
                            APPLICATION_HOST_INTERFACE_PORT_JSON(),
                            DtoHostInterfacePort.class);
            assertThat(
                    "bridge1 should contain the host-interface-port binding.",
                    hostInterfacePort2, is(notNullValue()));
            DtoHostInterfacePort hostInterfacePort2 =
                    hostTopology.getHostInterfacePort(
                            hostInterfacePortFromPort2.getPortId());
            assertThat("router1 should contain hostInterfacePort2",
                    hostInterfacePortFromPort2,
                    is(equalTo(hostInterfacePort2)));
        }
    }
}
