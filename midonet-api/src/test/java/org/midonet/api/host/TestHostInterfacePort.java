/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host;

import java.net.InetAddress;
import java.net.URI;
import java.util.UUID;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.sun.jersey.api.client.WebResource;

import org.apache.zookeeper.KeeperException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import org.midonet.api.VendorMediaType;
import org.midonet.api.host.rest_api.HostTopology;
import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.RestApiTestBase;
import org.midonet.api.rest_api.Topology;
import org.midonet.api.serialization.SerializationModule;
import org.midonet.api.validation.MessageProperty;
import org.midonet.api.zookeeper.StaticMockDirectory;
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
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.version.guice.VersionModule;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.midonet.api.VendorMediaType.APPLICATION_HOST_COLLECTION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_HOST_INTERFACE_PORT_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_PORT_V2_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_ROUTER_JSON_V2;

@RunWith(Enclosed.class)
public class TestHostInterfacePort {

    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";

    public static class TestCrud extends RestApiTestBase {

        private DtoWebResource dtoResource;
        private Topology topology;
        private HostTopology hostTopology;
        private HostZkManager hostManager;
        private MidonetApi api;
        private Injector injector = null;

        private UUID host1Id = UUID.randomUUID();

        public TestCrud() {
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
                return new ZkManager(directory, basePath);
            }

            @Provides @Singleton
            public HostZkManager provideHostZkManager(ZkManager zkManager,
                                                      PathBuilder paths,
                                                      Serializer serializer) {
                return new HostZkManager(zkManager, paths, serializer);
            }
        }

        @Before
        public void setUp() throws StateAccessException,
                InterruptedException, KeeperException, SerializationException {

            injector = Guice.createInjector(
                    new VersionModule(),
                    new SerializationModule(),
                    new TestModule(ZK_ROOT_MIDOLMAN));
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);
            hostManager = injector.getInstance(HostZkManager.class);

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

            hostTopology = new HostTopology.Builder(dtoResource, hostManager)
                    .create(host1Id, host1)
                    .create("tz1", tunnelZone1)
                    .build();

            URI baseUri = resource().getURI();
            api = new MidonetApi(baseUri.toString());
            api.enableLogging();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        private void bindHostToTunnelZone(UUID hostId) {
            DtoTunnelZone tz = hostTopology.getGreTunnelZone("tz1");
            Assert.assertNotNull(tz);
            // Map a tunnel zone to a host
            DtoTunnelZoneHost mapping = new DtoTunnelZoneHost();
            mapping.setHostId(hostId);
            // Now set the ip address and the create should succeed.
            mapping.setIpAddress("192.168.100.2");
            DtoTunnelZoneHost tzHost = dtoResource.postAndVerifyCreated(
                    tz.getHosts(),
                    VendorMediaType.APPLICATION_TUNNEL_ZONE_HOST_JSON,
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
                    APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON,
                    DtoHostInterfacePort[].class);
            Assert.assertEquals(0, maps.length);

            // Map a tunnel zone to a host
            DtoHostInterfacePort mapping1 = new DtoHostInterfacePort();
            mapping1.setPortId(port1.getId());
            mapping1.setInterfaceName("eth0");
            mapping1 = dtoResource.postAndVerifyCreated(
                    host.getPorts(),
                    APPLICATION_HOST_INTERFACE_PORT_JSON,
                    mapping1,
                    DtoHostInterfacePort.class);

            // List bridge mapping and verify that there is one
            maps = dtoResource.getAndVerifyOk(
                    host.getPorts(),
                    APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON,
                    DtoHostInterfacePort[].class);
            Assert.assertEquals(1, maps.length);

            // Remove mapping
            dtoResource.deleteAndVerifyNoContent(
                    mapping1.getUri(),
                    APPLICATION_HOST_INTERFACE_PORT_JSON);

            // List mapping and verify that there is none
            maps = dtoResource.getAndVerifyOk(
                    host.getPorts(),
                    APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON,
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
                    APPLICATION_HOST_INTERFACE_PORT_JSON,
                    mapping);
            assertErrorMatchesLiteral(error,
                    MessageProperty.getMessage(
                            MessageProperty.HOST_IS_NOT_IN_ANY_TUNNEL_ZONE));
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
                APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON,
                DtoHostInterfacePort[].class);
            Assert.assertEquals(0, maps.length);

            DtoHostInterfacePort mapping = new DtoHostInterfacePort();
            mapping.setPortId(port1.getId());
            mapping.setInterfaceName("eth0");
            dtoResource.postAndVerifyCreated(
                    host.getPorts(),
                    APPLICATION_HOST_INTERFACE_PORT_JSON,
                    mapping,
                    DtoHostInterfacePort.class);

            mapping = new DtoHostInterfacePort();
            mapping.setPortId(port2.getId());
            mapping.setInterfaceName("eth0");
            dtoResource.postAndVerifyBadRequest(
                    host.getPorts(),
                    APPLICATION_HOST_INTERFACE_PORT_JSON,
                    mapping);

        }

        @Test
        public void testClient() throws Exception {
            UUID hostId = UUID.randomUUID();

            HostDirectory.Metadata metadata = new HostDirectory.Metadata();
            metadata.setName("test");
            metadata.setAddresses(new InetAddress[]{
                InetAddress.getByAddress(
                    new byte[]{(byte) 193, (byte) 231, 30, (byte) 197})
            });

            hostManager.createHost(hostId, metadata);
            hostManager.makeAlive(hostId);

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
                            APPLICATION_PORT_V2_JSON,
                            port, DtoRouterPort.class);

            // Get the host DTO.
            DtoHost[] hosts = dtoResource.getAndVerifyOk(
                    topology.getApplication().getHosts(),
                    APPLICATION_HOST_COLLECTION_JSON,
                    DtoHost[].class);
            Assert.assertEquals(1, hosts.length);
            DtoHost resHost = hosts[0];
            bindHostToTunnelZone(resHost.getId());

            // Bind the exterior port to an interface on the host.
            DtoHostInterfacePort hostBinding = new DtoHostInterfacePort();
            hostBinding.setHostId(resHost.getId());
            hostBinding.setInterfaceName("eth0");
            hostBinding.setPortId(resPort.getId());
            DtoHostInterfacePort resPortBinding =
                    dtoResource.postAndVerifyCreated(resHost.getPorts(),
                            APPLICATION_HOST_INTERFACE_PORT_JSON, hostBinding,
                            DtoHostInterfacePort.class);
            dtoResource.deleteAndVerifyNoContent(
                    resRouter.getUri(), APPLICATION_ROUTER_JSON_V2);
        }
    }
}
