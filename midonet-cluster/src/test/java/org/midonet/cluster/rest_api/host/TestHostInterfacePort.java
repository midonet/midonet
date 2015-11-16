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
package org.midonet.cluster.rest_api.host;

import java.net.InetAddress;
import java.net.URI;
import java.util.UUID;

import com.sun.jersey.api.client.WebResource;

import org.apache.zookeeper.KeeperException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import org.midonet.cluster.rest_api.host.rest_api.HostTopology;
import org.midonet.cluster.rest_api.rest_api.DtoWebResource;
import org.midonet.cluster.rest_api.rest_api.FuncTest;
import org.midonet.cluster.rest_api.rest_api.RestApiTestBase;
import org.midonet.cluster.rest_api.rest_api.Topology;
import org.midonet.cluster.rest_api.rest_api.TopologyBackdoor;
import org.midonet.client.MidonetApi;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoBridgePort;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoHost;
import org.midonet.client.dto.DtoHostInterfacePort;
import org.midonet.client.dto.DtoRouter;
import org.midonet.client.dto.DtoRouterPort;
import org.midonet.client.dto.DtoTunnelZone;
import org.midonet.client.dto.DtoTunnelZoneHost;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.BridgePort;
import org.midonet.client.resource.Host;
import org.midonet.client.resource.HostInterfacePort;
import org.midonet.client.resource.ResourceCollection;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.cluster.backend.zookeeper.StateAccessException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.midonet.cluster.rest_api.validation.MessageProperty.HOST_INTERFACE_IS_USED;
import static org.midonet.cluster.rest_api.validation.MessageProperty.HOST_IS_NOT_IN_ANY_TUNNEL_ZONE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.PORT_ALREADY_BOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_HOST_COLLECTION_JSON_V3;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_HOST_INTERFACE_PORT_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_PORT_V3_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_ROUTER_JSON_V3;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TUNNEL_ZONE_HOST_JSON;

@RunWith(Enclosed.class)
public class TestHostInterfacePort {

    public static class TestCrud extends RestApiTestBase {

        private DtoWebResource dtoResource;
        private Topology topology;
        private HostTopology hostTopology;
        private MidonetApi api;

        private UUID host1Id = UUID.randomUUID();

        public TestCrud() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() throws StateAccessException,
                InterruptedException, KeeperException, SerializationException {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            DtoHost host1 = new DtoHost();
            host1.setName("host1");

            DtoBridge bridge1 = new DtoBridge();
            bridge1.setName("bridge1-name");
            bridge1.setTenantId("tenant1-id");

            DtoRouter router1 = new DtoRouter();
            router1.setName("router1-name");
            router1.setTenantId("tenant1-id");

            DtoBridgePort bridgePort1 = new DtoBridgePort();
            DtoBridgePort bridgePort2 = new DtoBridgePort();

            DtoTunnelZone tunnelZone1 = new DtoTunnelZone();
            tunnelZone1.setName("tz1-name");

            topology = new Topology.Builder(dtoResource)
                    .create("router1", router1)
                    .create("bridge1", bridge1)
                    .create("bridge1", "bridgePort1", bridgePort1)
                    .create("bridge1", "bridgePort2", bridgePort2)
                    .build();

            hostTopology = new HostTopology.Builder(dtoResource)
                    .create(host1Id, host1)
                    .create("tz1", tunnelZone1)
                    .build();

            URI baseUri = resource().getURI();
            api = new MidonetApi(baseUri.toString());
            api.enableLogging();
        }

        public TopologyBackdoor getTopologyBackdoor() {
            return FuncTest._injector.getInstance(TopologyBackdoor.class);
        }

        private void bindHostToTunnelZone(UUID hostId) {
            DtoTunnelZone tz = hostTopology.getGreTunnelZone("tz1");
            Assert.assertNotNull(tz);
            // Map a tunnel zone to a host
            DtoTunnelZoneHost mapping = new DtoTunnelZoneHost();
            mapping.setHostId(hostId);
            // Now set the ip address and the create should succeed.
            mapping.setIpAddress("192.168.100.2");
            dtoResource.postAndVerifyCreated(
                    tz.getHosts(),
                    APPLICATION_TUNNEL_ZONE_HOST_JSON(),
                    mapping,
                    DtoTunnelZoneHost.class);
        }

        @Test
        public void testCrud() throws Exception {
            DtoHost host = hostTopology.getHost(host1Id);
            DtoBridgePort port1 = topology.getBridgePort(
                    "bridgePort1");

            bindHostToTunnelZone(host1Id);

            // List mappings.  There should be none.
            DtoHostInterfacePort[] maps = dtoResource.getAndVerifyOk(
                    host.getPorts(),
                    APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON(),
                    DtoHostInterfacePort[].class);
            Assert.assertEquals(0, maps.length);

            // Map a tunnel zone to a host
            DtoHostInterfacePort mapping1 = new DtoHostInterfacePort();
            mapping1.setPortId(port1.getId());
            mapping1.setInterfaceName("eth0");
            mapping1 = dtoResource.postAndVerifyCreated(
                    host.getPorts(),
                    APPLICATION_HOST_INTERFACE_PORT_JSON(),
                    mapping1,
                    DtoHostInterfacePort.class);

            // List bridge mapping and verify that there is one
            maps = dtoResource.getAndVerifyOk(
                    host.getPorts(),
                    APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON(),
                    DtoHostInterfacePort[].class);
            Assert.assertEquals(1, maps.length);

            // Remove mapping
            dtoResource.deleteAndVerifyNoContent(
                    mapping1.getUri(),
                    APPLICATION_HOST_INTERFACE_PORT_JSON());

            // List mapping and verify that there is none
            maps = dtoResource.getAndVerifyOk(
                    host.getPorts(),
                    APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON(),
                    DtoHostInterfacePort[].class);

            Assert.assertEquals(0, maps.length);
        }

        @Test
        public void testCreateWhenHostIsNotInAnyTunnelZone() {
            DtoHost host = hostTopology.getHost(host1Id);
            DtoBridgePort port = topology.getBridgePort("bridgePort1");

            // Map a tunnel zone to a host
            DtoHostInterfacePort mapping = new DtoHostInterfacePort();
            mapping.setPortId(port.getId());
            mapping.setInterfaceName("eth0");
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    host.getPorts(),
                    APPLICATION_HOST_INTERFACE_PORT_JSON(),
                    mapping);
            assertErrorMatchesLiteral(error,
                    getMessage(HOST_IS_NOT_IN_ANY_TUNNEL_ZONE, host1Id));
        }

        @Test
        public void testCreateWhenInterfaceIsTaken() {
            DtoHost host = hostTopology.getHost(host1Id);
            DtoBridgePort port1 = topology.getBridgePort(
                    "bridgePort1");
            DtoBridgePort port2 = topology.getBridgePort(
                    "bridgePort2");

            bindHostToTunnelZone(host1Id);

            // List mappings.  There should be none.
            DtoHostInterfacePort[] maps = dtoResource.getAndVerifyOk(
                host.getPorts(),
                APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON(),
                DtoHostInterfacePort[].class);
            Assert.assertEquals(0, maps.length);

            DtoHostInterfacePort mapping = new DtoHostInterfacePort();
            mapping.setPortId(port1.getId());
            mapping.setInterfaceName("eth0");
            dtoResource.postAndVerifyCreated(
                    host.getPorts(),
                    APPLICATION_HOST_INTERFACE_PORT_JSON(),
                    mapping,
                    DtoHostInterfacePort.class);

            mapping = new DtoHostInterfacePort();
            mapping.setPortId(port2.getId());
            mapping.setInterfaceName("eth0");
            DtoError error = dtoResource.postAndVerifyBadRequest(
                host.getPorts(),
                APPLICATION_HOST_INTERFACE_PORT_JSON(),
                mapping);
            assertErrorMatchesLiteral(error,
                getMessage(HOST_INTERFACE_IS_USED, "eth0"));

        }

        @Test
        public void testCreateWhenAlreadyBound() {
            DtoHost host = hostTopology.getHost(host1Id);
            DtoBridgePort port1 = topology.getBridgePort(
                "bridgePort1");

            bindHostToTunnelZone(host1Id);

            // List mappings.  There should be none.
            DtoHostInterfacePort[] maps = dtoResource.getAndVerifyOk(
                host.getPorts(),
                APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON(),
                DtoHostInterfacePort[].class);
            Assert.assertEquals(0, maps.length);

            DtoHostInterfacePort mapping = new DtoHostInterfacePort();
            mapping.setPortId(port1.getId());
            mapping.setInterfaceName("eth0");
            dtoResource.postAndVerifyCreated(
                host.getPorts(),
                APPLICATION_HOST_INTERFACE_PORT_JSON(),
                mapping,
                DtoHostInterfacePort.class);

            mapping = new DtoHostInterfacePort();
            mapping.setPortId(port1.getId());
            mapping.setInterfaceName("eth1");
            DtoError error = dtoResource.postAndVerifyBadRequest(
                host.getPorts(),
                APPLICATION_HOST_INTERFACE_PORT_JSON(),
                mapping);
            assertErrorMatchesLiteral(error,
                getMessage(PORT_ALREADY_BOUND, port1.getId()));
        }

        @Test
        public void testClient() throws Exception {
            UUID hostId = UUID.randomUUID();

            TopologyBackdoor topBackdoor = getTopologyBackdoor();
            topBackdoor.createHost(hostId, "test", new InetAddress[]{
                InetAddress.getByAddress(
                    new byte[]{(byte) 193, (byte) 231, 30, (byte) 197})
            });
            topBackdoor.makeHostAlive(hostId);

            ResourceCollection<Host> hosts = api.getHosts();
            org.midonet.client.resource.Host host = hosts.get(0);

            // Create a bridge
            Bridge b1 = api.addBridge()
                            .tenantId("tenant-1")
                            .name("bridge-1")
                            .create();

            BridgePort bp1 = b1.addPort().create();
            BridgePort bp2 = b1.addPort().create();

            bindHostToTunnelZone(host.getId());
            HostInterfacePort hip1 = host.addHostInterfacePort()
                                                      .interfaceName("tap-1")
                                                      .portId(bp1.getId())
                                                      .create();
            HostInterfacePort hip2 = host.addHostInterfacePort()
                                                      .interfaceName("tap-2")
                                                      .portId(bp2.getId())
                                                      .create();

            ResourceCollection<HostInterfacePort> hips = host.getPorts();

            assertThat("There are two host interface port mappings.",
                       hips.size(), is(2));

            assertThat("Correct host id is returned", hip1.getHostId(),
                       is(host.getId()));
            assertThat("Correct host id is returned", hip2.getHostId(),
                       is(host.getId()));

            assertThat("Correct port id is returned",
                       hip1.getPortId(), is(bp1.getId()));
            assertThat("Correct port id is returned",
                       hip2.getPortId(), is(bp2.getId()));

            assertThat("Correct interface name is returned",
                       hip1.getInterfaceName(), is("tap-1"));
            assertThat("Correct interface name is returned",
                       hip2.getInterfaceName(), is("tap-2"));
        }

        @Test
        public void testRouterDeleteWithBoundExteriorPort() throws Exception {
            // Add a router
            DtoRouter resRouter = topology.getRouter("router1");
            // Add an exterior port.
            DtoRouterPort port = new DtoRouterPort();
            port.setNetworkAddress("10.0.0.0");
            port.setNetworkLength(24);
            port.setPortAddress("10.0.0.1");
            DtoRouterPort resPort =
                    dtoResource.postAndVerifyCreated(resRouter.getPorts(),
                            APPLICATION_PORT_V3_JSON(),
                            port, DtoRouterPort.class);

            // Get the host DTO.
            DtoHost[] hosts = dtoResource.getAndVerifyOk(
                    topology.getApplication().getHosts(),
                    APPLICATION_HOST_COLLECTION_JSON_V3(),
                    DtoHost[].class);
            Assert.assertEquals(1, hosts.length);
            DtoHost resHost = hosts[0];
            bindHostToTunnelZone(resHost.getId());

            // Bind the exterior port to an interface on the host.
            DtoHostInterfacePort hostBinding = new DtoHostInterfacePort();
            hostBinding.setHostId(resHost.getId());
            hostBinding.setInterfaceName("eth0");
            hostBinding.setPortId(resPort.getId());
            dtoResource.postAndVerifyCreated(resHost.getPorts(),
                 APPLICATION_HOST_INTERFACE_PORT_JSON(), hostBinding,
                 DtoHostInterfacePort.class);
            dtoResource.deleteAndVerifyNoContent(resRouter.getUri(),
                                                 APPLICATION_ROUTER_JSON_V3());
        }
    }
}
