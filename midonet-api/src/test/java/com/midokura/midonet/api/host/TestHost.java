/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midonet.api.host;

import java.net.InetAddress;
import java.net.URI;
import java.util.UUID;

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

import com.midokura.midolman.host.state.HostDirectory;
import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midonet.api.VendorMediaType;
import com.midokura.midonet.api.rest_api.FuncTest;
import com.midokura.midonet.api.zookeeper.StaticMockDirectory;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkPathManager;
import com.midokura.midonet.client.MidonetMgmt;
import com.midokura.midonet.client.dto.DtoHost;
import com.midokura.midonet.client.dto.DtoInterface;
import com.midokura.midonet.client.exception.HttpForbiddenException;
import com.midokura.midonet.client.resource.*;
import com.midokura.midonet.client.resource.Host;
import com.midokura.packets.MAC;
import static com.midokura.midonet.api.VendorMediaType.APPLICATION_HOST_COLLECTION_JSON;
import static com.midokura.midonet.api.VendorMediaType.APPLICATION_HOST_JSON;
import static com.midokura.midonet.api.VendorMediaType.APPLICATION_INTERFACE_COLLECTION_JSON;

public class TestHost extends JerseyTest {

    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";

    private HostZkManager hostManager;
    private ZkPathManager pathManager;
    private Directory rootDirectory;
    private MidonetMgmt mgmt;

    public TestHost() {
        super(createWebApp());

        pathManager = new ZkPathManager(ZK_ROOT_MIDOLMAN);
    }

    private static AppDescriptor createWebApp() {

        return FuncTest.getBuilder().build();

    }

    // This one also tests Create with given tenant ID string
    @Before
    public void before() throws KeeperException {
        resource().type(VendorMediaType.APPLICATION_JSON)
            .get(ClientResponse.class);

        rootDirectory = StaticMockDirectory.getDirectoryInstance();

        hostManager = new HostZkManager(rootDirectory, "/test/midolman");

        URI baseUri = resource().getURI();
        mgmt = new MidonetMgmt(baseUri.toString());
        mgmt.enableLogging();
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

        ResourceCollection<Host> hosts = mgmt.getHosts();
        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("Hosts should be empty", hosts.size(), equalTo(0));
        hostManager.createHost(hostId, metadata);

        hosts = mgmt.getHosts();

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.size(), equalTo(1));
        assertThat("The returned host should have the same UUID",
                   hosts.get(0).getId(), equalTo(hostId));
        assertThat("The host should not be alive",
                   hosts.get(0).isAlive(), equalTo(false));

        metadata.setName("emporiki1");
        hostManager.makeAlive(hostId, metadata);

        hosts = mgmt.getHosts();

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.size(), equalTo(1));
        assertThat("The returned host should have the same UUID",
                   hosts.get(0).getId(), equalTo(hostId));
        assertThat("The host should be reported as alive",
                   hosts.get(0).isAlive(), equalTo(true));
    }

    @Test
    public void testOneHostNoAddresses() throws Exception {
        UUID hostId = UUID.randomUUID();

        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("testhost");
        hostManager.createHost(hostId, metadata);

        ResourceCollection<Host> hosts = mgmt.getHosts();

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

        ResourceCollection<Host> hosts = mgmt.getHosts();

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

        ResourceCollection<Host> hosts = mgmt.getHosts();

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
                   !rootDirectory.has(pathManager.getHostPath(hostId)));
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
        hostManager.makeAlive(hostId, metadata);


        ResourceCollection<Host> hosts = mgmt.getHosts();

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
                   rootDirectory.has(pathManager.getHostPath(hostId)),
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

        ResourceCollection<Host> hosts = mgmt.getHosts();
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

        ResourceCollection<Host> hosts = mgmt.getHosts();
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

        ResourceCollection<Host> hosts = mgmt.getHosts();

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
