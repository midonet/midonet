/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.api.host;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.UUID;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.Injector;
import com.google.inject.Guice;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.JerseyTest;
import org.apache.zookeeper.KeeperException;
import org.junit.*;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import org.midonet.api.serialization.SerializationModule;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.Topology;
import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.ZkPathManager;
import org.midonet.client.MidonetApi;
import org.midonet.client.dto.DtoHost;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoPort;
import org.midonet.client.dto.DtoBridgePort;
import org.midonet.client.dto.DtoInterface;
import org.midonet.client.exception.HttpForbiddenException;
import org.midonet.client.resource.*;
import org.midonet.client.resource.Host;
import org.midonet.client.VendorMediaType;
import org.midonet.midolman.version.guice.VersionModule;
import org.midonet.packets.MAC;

import static org.midonet.client.VendorMediaType.APPLICATION_HOST_COLLECTION_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_HOST_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_BRIDGE_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_PORT_V2_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_INTERFACE_COLLECTION_JSON;

public class TestHost extends JerseyTest {

    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";

    private HostZkManager hostManager;
    private ZkPathManager pathManager;
    private DtoWebResource dtoResource;
    private Topology topology;
    private Directory dir;
    private MidonetApi api;
    private static Injector injector;

    public TestHost() {
        super(createWebApp());

        pathManager = new ZkPathManager(ZK_ROOT_MIDOLMAN);
    }

    private static AppDescriptor createWebApp() {

        return FuncTest.getBuilder().build();

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

        @Provides @Singleton
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

    private DtoBridge addBridge(String bridgeName) {
        DtoBridge bridge = new DtoBridge();
        bridge.setName(bridgeName);
        bridge.setTenantId("tenant1");
        bridge = dtoResource.postAndVerifyCreated(
            topology.getApplication().getBridges(),
            APPLICATION_BRIDGE_JSON, bridge, DtoBridge.class);
        return bridge;
    }

    private DtoBridgePort addPort(DtoBridge bridge) {
        DtoBridgePort port = new DtoBridgePort();
        port = dtoResource.postAndVerifyCreated(bridge.getPorts(),
            APPLICATION_PORT_V2_JSON, port, DtoBridgePort.class);
        return port;
    }

    // This one also tests Create with given tenant ID string
    @Before
    public void before() throws KeeperException, InterruptedException {
        dtoResource = new DtoWebResource(resource());
        injector = Guice.createInjector(
                new VersionModule(),
                new SerializationModule(),
                new TestModule(ZK_ROOT_MIDOLMAN));
        dir = injector.getInstance(Directory.class);
        resource().type(VendorMediaType.APPLICATION_JSON_V2)
                .accept(VendorMediaType.APPLICATION_JSON_V2)
                .get(ClientResponse.class);

        topology = new Topology.Builder(dtoResource).build();
        hostManager = injector.getInstance(HostZkManager.class);
        URI baseUri = resource().getURI();
        api = new MidonetApi(baseUri.toString());
        api.enableLogging();
    }

    @After
    public void resetDirectory() throws Exception {
        StaticMockDirectory.clearDirectoryInstance();
    }


    @Test
    public void testNoHosts() throws Exception {
        ClientResponse response = resource()
            .path("hosts/")
            .type(APPLICATION_HOST_COLLECTION_JSON).get(ClientResponse.class);

        assertThat("We should have a proper response",
                   response, is(notNullValue()));

        assertThat(
            "No hosts is not an error situation",
            response.getClientResponseStatus(),
            equalTo(ClientResponse.Status.OK));

        ClientResponse clientResponse = resource()
            .path("hosts/" + UUID.randomUUID().toString())
            .type(APPLICATION_HOST_JSON).get(ClientResponse.class);

        assertThat(clientResponse.getClientResponseStatus(),
                   equalTo(ClientResponse.Status.NOT_FOUND));
    }

    @Test
    public void testAliveHost() throws Exception {
        UUID hostId = UUID.randomUUID();

        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("semporiki");

        ResourceCollection<Host> hosts = api.getHosts();
        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("Hosts should be empty", hosts.size(), equalTo(0));
        hostManager.createHost(hostId, metadata);

        hosts = api.getHosts();

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.size(), equalTo(1));
        assertThat("The returned host should have the same UUID",
                   hosts.get(0).getId(), equalTo(hostId));
        assertThat("The host should not be alive",
                   hosts.get(0).isAlive(), equalTo(false));

        hostManager.makeAlive(hostId);

        hosts = api.getHosts();

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.size(), equalTo(1));
        assertThat("The returned host should have the same UUID",
                   hosts.get(0).getId(), equalTo(hostId));
        assertThat("The host should be reported as alive",
                   hosts.get(0).isAlive(), equalTo(true));
    }

    @Test
    public void testDeadHostWithPortMapping() throws Exception {
        UUID hostId = UUID.randomUUID();

        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("testDeadhost");
        hostManager.createHost(hostId, metadata);
        // Don't make this host alive. We are testing deleting while dead.

        DtoBridge bridge = addBridge("testBridge");
        DtoPort port = addPort(bridge);
        hostManager.addVirtualPortMapping(hostId,
                new HostDirectory.VirtualPortMapping(port.getId(), "BLAH"));

        ResourceCollection<Host> hosts = api.getHosts();
        Host deadHost = hosts.get(0);
        boolean caught403 = false;
        try {
            deadHost.delete();
        } catch (HttpForbiddenException ex) {
            caught403 = true;
        }
        assertThat("Deletion of host got 403", caught403, is(true));
        assertThat("Host was not removed from zk",
                   dir.has(pathManager.getHostPath(hostId)),
                   equalTo(true));

        hostManager.delVirtualPortMapping(hostId, port.getId());
        deadHost.delete();

        assertThat("Host was removed from zk",
                   dir.has(pathManager.getHostPath(hostId)),
                   equalTo(false));
    }

    @Test
    public void testOneHostNoAddresses() throws Exception {
        UUID hostId = UUID.randomUUID();

        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("testhost");
        hostManager.createHost(hostId, metadata);

        ResourceCollection<Host> hosts = api.getHosts();

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.size(), equalTo(1));

        assertThat(
            "The returned host should have the same UUID as the one in ZK",
            hosts.get(0).getId(), equalTo(hostId));
        assertThat("The host should not be alive",
                   hosts.get(0).isAlive(), equalTo(false));
    }

    @Test
    public void testOneHost() throws Exception {

        UUID hostId = UUID.randomUUID();

        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("testhost");
        metadata.setAddresses(new InetAddress[]{
            InetAddress.getByAddress(new byte[]{127, 0, 0, 1}),
            InetAddress.getByAddress(
                new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 127, 0, 0, 1}),
        });
        hostManager.createHost(hostId, metadata);

        ResourceCollection<Host> hosts = api.getHosts();

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.size(), equalTo(1));

        Host host = hosts.get(0);

        assertThat(
            "The returned host should have the same UUID as the one in ZK",
            host.getId(), equalTo(hostId));
        assertThat(
            "The returned host should have the same name as the one in ZK",
            host.getName(), equalTo(metadata.getName()));
        assertThat("The returned host should have a not null address list",
                   host.getAddresses(), not(nullValue()));
        assertThat(
            "The returned host should have the same number of addresses as the metadata",
            host.getAddresses().length,
            equalTo(metadata.getAddresses().length));
        assertThat("The host should not be alive",
                   host.isAlive(), equalTo(false));

        InetAddress[] addrs = metadata.getAddresses();
        for (int i = 0; i < addrs.length; i++) {
            InetAddress inetAddress = addrs[i];

            assertThat("Returned address in the dto should not be null",
                       host.getAddresses()[i], not(nullValue()));

            assertThat("Address from the Dto should match the one in metadata",
                       host.getAddresses()[i], equalTo(inetAddress.toString()));
        }
    }

    @Test
    public void testDeleteDeadHost() throws Exception {
        UUID hostId = UUID.randomUUID();

        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("testhost");
        metadata.setAddresses(new InetAddress[]{
            InetAddress.getByAddress(new byte[]{127, 0, 0, 1}),
        });
        hostManager.createHost(hostId, metadata);

        ResourceCollection<Host> hosts = api.getHosts();

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.size(), equalTo(1));
        assertThat(
            "The returned host should have the same UUID as the one in ZK",
            hosts.get(0).getId(), equalTo(hostId));
        assertThat(
            "The returned host should have the same UUID as the one in ZK",
            hosts.get(0).isAlive(), equalTo(false));

//       assertThat("The host delete was successful",
//                   response.getClientResponseStatus(),
//                   equalTo(ClientResponse.Status.NO_CONTENT));

        //NOTE: right now client library doesn't store http response status.
        //       maybe store the most recent response object in resource object.
        hosts.get(0).delete();

        assertThat("Host should have been removed from ZooKeeper.",
                   !dir.has(pathManager.getHostPath(hostId)));
    }

    @Test
    public void testDeleteAliveHost() throws Exception {
        UUID hostId = UUID.randomUUID();

        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("testhost");
        metadata.setAddresses(new InetAddress[]{
            InetAddress.getByAddress(new byte[]{127, 0, 0, 1}),
        });
        hostManager.createHost(hostId, metadata);
        hostManager.makeAlive(hostId);


        ResourceCollection<Host> hosts = api.getHosts();

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.size(), equalTo(1));

        Host h1 = hosts.get(0);
        assertThat(
            "The returned host should have the same UUID as the one in ZK",
            h1.getId(), equalTo(hostId));
        assertThat(
            "The returned host should have the same UUID as the one in ZK",
            h1.isAlive(), equalTo(true));


        boolean caught403 = false;
        try {
            h1.delete();
        } catch (HttpForbiddenException ex) {
            caught403 = true;

        }
        assertThat("Deletion of host got 403", caught403, is(true));
        assertThat("Host was not removed from zk",
                   dir.has(pathManager.getHostPath(hostId)),
                   equalTo(true));
    }

    @Test
    public void testHostWithoutInterface() throws Exception {
        UUID hostId = UUID.randomUUID();

        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("test");
        metadata.setAddresses(new InetAddress[]{
            InetAddress.getByAddress(
                new byte[]{(byte) 193, (byte) 231, 30, (byte) 197})
        });

        hostManager.createHost(hostId, metadata);
        hostManager.makeAlive(hostId);

        DtoHost host = resource()
            .path("hosts/" + hostId.toString())
            .type(APPLICATION_HOST_JSON)
            .get(DtoHost.class);

        assertThat(host, is(notNullValue()));

        ClientResponse clientResponse = resource()
            .uri(host.getInterfaces())
            .type(APPLICATION_INTERFACE_COLLECTION_JSON)
            .get(ClientResponse.class);

        assertThat(clientResponse, is(notNullValue()));

        assertThat(clientResponse.getClientResponseStatus(),
                   equalTo(ClientResponse.Status.OK));

        ResourceCollection<Host> hosts = api.getHosts();
        Host h = hosts.get(0);
        ResourceCollection<HostInterface> hIfaces = h.getInterfaces();

        assertThat("Host doesn't have any interfaces", hIfaces.size(),
                   equalTo(0));

    }

    @Test
    public void testHostWithOneInterface() throws Exception {
        UUID hostId = UUID.randomUUID();

        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("test");
        metadata.setAddresses(new InetAddress[]{
            InetAddress.getByAddress(
                new byte[]{(byte) 193, (byte) 231, 30, (byte) 197})
        });

        hostManager.createHost(hostId, metadata);
        hostManager.makeAlive(hostId);

        HostDirectory.Interface anInterface = new HostDirectory.Interface();

        anInterface.setName("eth0");
        anInterface.setMac(MAC.fromString("16:1f:5c:19:a0:60").getAddress());
        anInterface.setMtu(123);
        anInterface.setType(HostDirectory.Interface.Type.Physical);
        anInterface.setAddresses(new InetAddress[]{
            InetAddress.getByAddress(new byte[]{10, 10, 10, 1})
        });

        hostManager.createInterface(hostId, anInterface);

        ResourceCollection<Host> hosts = api.getHosts();
        Host host = hosts.get(0);

        ResourceCollection<HostInterface> hIfaces = host.getInterfaces();


        assertThat("The host should return a proper interfaces object",
                   hIfaces, is(notNullValue()));

        assertThat(hIfaces.size(), equalTo(1));

        HostInterface hIface = hIfaces.get(0);

        assertThat("The DtoInterface object should have a proper host id",
                   hIface.getHostId(), equalTo(hostId));
        assertThat("The DtoInterface object should have a proper name",
                   hIface.getName(), equalTo(anInterface.getName()));
        assertThat("The DtoInterface should have a proper MTU valued",
                   hIface.getMtu(), equalTo(anInterface.getMtu()));
        assertThat("The DtoInterface should have the proper mac address",
                   hIface.getMac(),
                   equalTo(new MAC(anInterface.getMac()).toString()));
        assertThat("The DtoInterface type should be returned properly",
                   hIface.getType(), equalTo(DtoInterface.Type.Physical));
    }

    @Test
    public void testTwoHosts() throws Exception {
        UUID host1 = UUID.randomUUID();
        UUID host2 = UUID.randomUUID();

        HostDirectory.Metadata metadata1 = new HostDirectory.Metadata();
        metadata1.setName("host1");

        HostDirectory.Metadata metadata2 = new HostDirectory.Metadata();
        metadata1.setName("host2");

        hostManager.createHost(host1, metadata1);
        hostManager.createHost(host2, metadata2);

        ResourceCollection<Host> hosts = api.getHosts();

        assertThat("We should have a proper array of hosts returned",
                   hosts.size(), equalTo(2));
    }

    @Test
    public void testInterfaceUriExists() throws Exception {
        UUID hostId = UUID.randomUUID();

        HostDirectory.Metadata hostMetadata = new HostDirectory.Metadata();
        hostMetadata.setName("host1");

        hostManager.createHost(hostId, hostMetadata);

        HostDirectory.Interface hostInterface = new HostDirectory.Interface();
        hostInterface.setName("test");
        hostManager.createInterface(hostId, hostInterface);

        DtoHost host = resource()
            .path("hosts/" + hostId.toString())
            .type(VendorMediaType.APPLICATION_HOST_JSON)
            .get(DtoHost.class);

        DtoInterface[] interfaces = resource()
            .uri(host.getInterfaces())
            .type(VendorMediaType.APPLICATION_INTERFACE_COLLECTION_JSON)
            .get(DtoInterface[].class);

        assertThat("There should be one interface description for the host",
                   interfaces, arrayWithSize(1));

        DtoInterface dtoHostInterface = interfaces[0];
        assertThat("the host dto should be properly configured",
                   dtoHostInterface,
                   allOf(notNullValue(), hasProperty("uri", notNullValue())));
    }

    @Test
    public void testInterfaceUriIsValid() throws Exception {
        UUID hostId = UUID.randomUUID();

        HostDirectory.Metadata hostMetadata = new HostDirectory.Metadata();
        hostMetadata.setName("host1");

        hostManager.createHost(hostId, hostMetadata);

        HostDirectory.Interface hostInterface = new HostDirectory.Interface();
        hostInterface.setName("test");
        hostManager.createInterface(hostId, hostInterface);

        DtoHost host = resource()
            .path("hosts/" + hostId.toString())
            .type(VendorMediaType.APPLICATION_HOST_JSON)
            .get(DtoHost.class);

        DtoInterface[] interfaces = resource()
            .uri(host.getInterfaces())
            .type(VendorMediaType.APPLICATION_INTERFACE_COLLECTION_JSON)
            .get(DtoInterface[].class);

        assertThat("There should be one interface description for the host",
                   interfaces, arrayWithSize(1));

        DtoInterface dtoHostInterface = interfaces[0];
        assertThat("the host dto should be properly configured",
                   dtoHostInterface,
                   allOf(notNullValue(), hasProperty("uri", notNullValue())));

        DtoInterface rereadInterface = resource()
            .uri(dtoHostInterface.getUri())
            .type(VendorMediaType.APPLICATION_INTERFACE_JSON)
            .get(DtoInterface.class);

        assertThat(
            "The interface should be properly retrieved when requested by uri",
            rereadInterface, notNullValue());

        assertThat(
            "When retrieving by uri we should get the same interface object",
            rereadInterface.getId(), equalTo(dtoHostInterface.getId()));
    }
}
