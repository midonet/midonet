/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.Injector;
import com.google.inject.Guice;
import org.apache.zookeeper.KeeperException;
import org.midonet.api.serialization.SerializationModule;
import org.midonet.client.dto.*;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.api.VendorMediaType;
import org.midonet.api.host.rest_api.HostTopology;
import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.Topology;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.client.MidonetApi;
import org.midonet.client.resource.*;
import org.midonet.client.resource.Host;
import org.midonet.client.resource.HostInterfacePort;

import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import junit.framework.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.midonet.midolman.version.guice.VersionModule;

import java.net.InetAddress;
import java.net.URI;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

@RunWith(Enclosed.class)
public class TestHostInterfacePort {

    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";

    public static class TestCrud extends JerseyTest {

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

            DtoBridgePort bridgePort1 = new DtoBridgePort();
            DtoBridgePort bridgePort2 = new DtoBridgePort();

            topology = new Topology.Builder(dtoResource)
                    .create("bridge1", bridge1)
                    .create("bridge1", "bridgePort1", bridgePort1)
                    .create("bridge1", "bridgePort2", bridgePort2)
                    .build();

            hostTopology = new HostTopology.Builder(dtoResource, hostManager)
                    .create(host1Id, host1).build();

            URI baseUri = resource().getURI();
            api = new MidonetApi(baseUri.toString());
            api.enableLogging();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Test
        public void testCrud() throws Exception {

            DtoHost host = hostTopology.getHost(host1Id);
            DtoBridgePort port1 = topology.getBridgePort(
                    "bridgePort1");

            // List mappings.  There should be none.
            DtoHostInterfacePort[] maps = dtoResource.getAndVerifyOk(
                    host.getPorts(),
                    VendorMediaType
                            .APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON,
                    DtoHostInterfacePort[].class);
            Assert.assertEquals(0, maps.length);

            // Map a tunnel zone to a host
            DtoHostInterfacePort mapping1 = new DtoHostInterfacePort();
            mapping1.setPortId(port1.getId());
            mapping1.setInterfaceName("eth0");
            mapping1 = dtoResource.postAndVerifyCreated(
                    host.getPorts(),
                    VendorMediaType.APPLICATION_HOST_INTERFACE_PORT_JSON,
                    mapping1,
                    DtoHostInterfacePort.class);

            // List bridge mapping and verify that there is one
            maps = dtoResource.getAndVerifyOk(
                    host.getPorts(),
                    VendorMediaType
                            .APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON,
                    DtoHostInterfacePort[].class);
            Assert.assertEquals(1, maps.length);

            // Remove mapping
            dtoResource.deleteAndVerifyNoContent(
                    mapping1.getUri(),
                    VendorMediaType.APPLICATION_HOST_INTERFACE_PORT_JSON);

            // List mapping and verify that there is none
            maps = dtoResource.getAndVerifyOk(
                    host.getPorts(),
                    VendorMediaType
                            .APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON,
                    DtoHostInterfacePort[].class);

            Assert.assertEquals(0, maps.length);
        }

        @Test
        public void testCreateWhenInterfaceIsTaken() {
            DtoHost host = hostTopology.getHost(host1Id);
            DtoBridgePort port1 = topology.getBridgePort(
                    "bridgePort1");
            DtoBridgePort port2 = topology.getBridgePort(
                    "bridgePort2");

            // List mappings.  There should be none.
            DtoHostInterfacePort[] maps = dtoResource.getAndVerifyOk(
                host.getPorts(),
                VendorMediaType
                    .APPLICATION_HOST_INTERFACE_PORT_COLLECTION_JSON,
                DtoHostInterfacePort[].class);
            Assert.assertEquals(0, maps.length);

            DtoHostInterfacePort mapping = new DtoHostInterfacePort();
            mapping.setPortId(port1.getId());
            mapping.setInterfaceName("eth0");
            dtoResource.postAndVerifyCreated(
                host.getPorts(),
                VendorMediaType.APPLICATION_HOST_INTERFACE_PORT_JSON,
                mapping,
                DtoHostInterfacePort.class);

            mapping = new DtoHostInterfacePort();
            mapping.setPortId(port2.getId());
            mapping.setInterfaceName("eth0");
            dtoResource.postAndVerifyBadRequest(
                host.getPorts(),
                VendorMediaType.APPLICATION_HOST_INTERFACE_PORT_JSON,
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
    }
}
