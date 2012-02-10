/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.rest_api;

import java.net.InetAddress;
import java.util.UUID;
import javax.ws.rs.core.UriBuilder;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.json.JSONConfiguration;
import com.sun.jersey.test.framework.AppDescriptor;
import com.sun.jersey.test.framework.JerseyTest;
import com.sun.jersey.test.framework.WebAppDescriptor;
import org.apache.zookeeper.KeeperException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.midokura.midolman.agent.state.HostDirectory;
import com.midokura.midolman.agent.state.HostZkManager;
import com.midokura.midolman.mgmt.data.StaticMockDaoFactory;
import com.midokura.midolman.mgmt.data.dto.client.DtoHost;
import com.midokura.midolman.mgmt.data.dto.client.DtoInterface;
import com.midokura.midolman.mgmt.rest_api.core.VendorMediaType;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkPathManager;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_HOST_COLLECTION_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_HOST_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_INTERFACE_COLLECTION_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_PORT_JSON;

public class TestHost extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(TestHost.class);
    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";

    private HostZkManager hostManager;
    private ZkPathManager pathManager;
    private Directory rootDirectory;

    public TestHost() {
        super(createWebApp());

        pathManager = new ZkPathManager(ZK_ROOT_MIDOLMAN);
    }

    private static AppDescriptor createWebApp() {
        return
            new WebAppDescriptor.Builder()
                .initParam(JSONConfiguration.FEATURE_POJO_MAPPING, "true")
                .initParam(
                    "com.sun.jersey.spi.container.ContainerRequestFilters",
                    "com.midokura.midolman.mgmt.auth.NoAuthFilter")
                .initParam("javax.ws.rs.Application",
                           "com.midokura.midolman.mgmt.rest_api.RestApplication")
                .contextParam("version", "1")
                .contextParam("datastore_service",
                              "com.midokura.midolman.mgmt.data.StaticMockDaoFactory")
                .contextParam("authorizer",
                              "com.midokura.midolman.mgmt.auth.SimpleAuthorizer")
                .contextParam("zk_conn_string", "")
                .contextParam("zk_timeout", "0")
                .contextParam("zk_root", ZK_ROOT_MIDOLMAN)
                .contextParam("zk_mgmt_root", "/test/midolman-mgmt")
                .contextPath("/test")
                .clientConfig(FuncTest.config)
                .build();
    }

    // This one also tests Create with given tenant ID string
    @Before
    public void before() throws KeeperException {
        resource().type(VendorMediaType.APPLICATION_JSON)
            .get(ClientResponse.class);

        rootDirectory = StaticMockDaoFactory.getFactoryInstance()
                                            .getDirectory();

        hostManager = new HostZkManager(rootDirectory, "/test/midolman");
    }

    @After
    public void resetDirectory() throws Exception {
        StaticMockDaoFactory.clearFactoryInstance();
    }


    @Test
    public void testNoHosts() throws Exception {
        ClientResponse response = resource()
            .path("hosts/")
            .type(APPLICATION_HOST_COLLECTION_JSON).get(ClientResponse.class);

        assertThat("We should have a proper response",
                   response, is(notNullValue()));
        assertThat("When no hosts are registered we should return an HTTP NO_RESPONSE code",
                   response.getClientResponseStatus(),
                   equalTo(ClientResponse.Status.NO_CONTENT));

        ClientResponse clientResponse = resource()
            .path("hosts/" + UUID.randomUUID().toString())
            .type(APPLICATION_HOST_JSON).get(ClientResponse.class);

        assertThat(clientResponse.getClientResponseStatus(),
                   equalTo(ClientResponse.Status.NO_CONTENT));
    }

    @Test
    public void testAliveHost() throws Exception {
        UUID hostId = UUID.randomUUID();

        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("semporiki");

        hostManager.createHost(hostId, metadata);

        DtoHost[] hosts = resource()
            .path("hosts/")
            .type(APPLICATION_HOST_COLLECTION_JSON).get(DtoHost[].class);

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.length, equalTo(1));
        assertThat("The returned host should have the same UUID",
                   hosts[0].getId(), equalTo(hostId));
        assertThat("The host should not be alive",
                   hosts[0].isAlive(), equalTo(false));

        metadata.setName("emporiki1");
        hostManager.makeAlive(hostId, metadata);

        hosts = resource()
            .path("hosts/")
            .type(APPLICATION_HOST_COLLECTION_JSON).get(DtoHost[].class);

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.length, equalTo(1));
        assertThat("The returned host should have the same UUID",
                   hosts[0].getId(), equalTo(hostId));
        assertThat("The host should be reported as alive",
                   hosts[0].isAlive(), equalTo(true));
    }

    @Test
    public void testOneHostNoAddresses() throws Exception {
        UUID hostId = UUID.randomUUID();

        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("testhost");
        hostManager.createHost(hostId, metadata);

        DtoHost[] hosts = resource()
            .path("hosts/")
            .type(APPLICATION_HOST_COLLECTION_JSON).get(DtoHost[].class);

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.length, equalTo(1));

        assertThat(
            "The returned host should have the same UUID as the one in ZK",
            hosts[0].getId(), equalTo(hostId));
        assertThat("The host should not be alive",
                   hosts[0].isAlive(), equalTo(false));
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

        DtoHost[] hosts = resource()
            .path("hosts/")
            .type(APPLICATION_HOST_COLLECTION_JSON).get(DtoHost[].class);

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.length, equalTo(1));

        DtoHost host = hosts[0];

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

        DtoHost[] hosts = resource()
            .path("hosts/")
            .type(APPLICATION_HOST_COLLECTION_JSON).get(DtoHost[].class);

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.length, equalTo(1));
        assertThat(
            "The returned host should have the same UUID as the one in ZK",
            hosts[0].getId(), equalTo(hostId));
        assertThat(
            "The returned host should have the same UUID as the one in ZK",
            hosts[0].isAlive(), equalTo(false));

        ClientResponse response = resource()
            .path("hosts/" + hostId.toString())
            .type(APPLICATION_PORT_JSON).delete(ClientResponse.class);

        assertThat("The host delete was successful",
                   response.getClientResponseStatus(),
                   equalTo(ClientResponse.Status.NO_CONTENT));

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

        DtoHost[] hosts = resource()
            .path("hosts/")
            .type(APPLICATION_HOST_COLLECTION_JSON).get(DtoHost[].class);

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.length, equalTo(1));
        assertThat(
            "The returned host should have the same UUID as the one in ZK",
            hosts[0].getId(), equalTo(hostId));
        assertThat(
            "The returned host should have the same UUID as the one in ZK",
            hosts[0].isAlive(), equalTo(true));

        ClientResponse response = resource()
            .path("hosts/" + hostId.toString())
            .type(APPLICATION_PORT_JSON).delete(ClientResponse.class);

        assertThat("The delete return code should be correct",
                   response.getStatus(), equalTo(403));

        assertThat("Host was not removed from zk",
                   rootDirectory.has(pathManager.getHostPath(hostId)), equalTo(true));
    }

    @Test
    public void testHostWithoutInterface() throws Exception {
        UUID hostId = UUID.randomUUID();

        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("test");
        metadata.setAddresses(new InetAddress[]{
            InetAddress.getByAddress(new byte[]{(byte)193, (byte)231, 30, (byte)197})
        });

        hostManager.createHost(hostId, metadata);
        hostManager.makeAlive(hostId);

        DtoHost host = resource()
            .path("hosts/" + hostId.toString())
            .type(APPLICATION_HOST_JSON)
            .get(DtoHost.class);

        assertThat(host, is(notNullValue()));

        assertThat(host.getInterfaces(),
                   equalTo(UriBuilder.fromUri(host.getUri()).path("/interfaces").build()));

        ClientResponse clientResponse = resource()
            .uri(host.getInterfaces())
            .type(APPLICATION_INTERFACE_COLLECTION_JSON)
            .get(ClientResponse.class);

        assertThat(clientResponse, is(notNullValue()));

        assertThat(clientResponse.getClientResponseStatus(),
                   equalTo(ClientResponse.Status.NO_CONTENT));
    }

    @Test
    public void testHostWithOneInterface() throws Exception {
        UUID hostId = UUID.randomUUID();

        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("test");
        metadata.setAddresses(new InetAddress[]{
            InetAddress.getByAddress(new byte[]{(byte)193, (byte)231, 30, (byte)197})
        });

        hostManager.createHost(hostId, metadata);
        hostManager.makeAlive(hostId);

        HostDirectory.Interface anInterface = new HostDirectory.Interface();

        anInterface.setName("eth0");
        anInterface.setMac(MAC.fromString("16:1f:5c:19:a0:60").getAddress());
        anInterface.setMtu(123);
        anInterface.setType(HostDirectory.Interface.Type.Physical);
        anInterface.setAddresses(new InetAddress[] {
            InetAddress.getByAddress(new byte[] {10, 10, 10, 1})
        });

        UUID interfaceId = hostManager.createInterface(hostId, anInterface);

        DtoHost host = resource()
            .path("hosts/" + hostId.toString())
            .type(APPLICATION_HOST_JSON)
            .get(DtoHost.class);

        assertThat(host, is(notNullValue()));

        DtoInterface[] interfaces = resource()
            .uri(host.getInterfaces())
            .type(VendorMediaType.APPLICATION_INTERFACE_COLLECTION_JSON)
            .get(DtoInterface[].class);

        assertThat("The host should return a proper interfaces object",
                   interfaces, is(notNullValue()));

        assertThat(interfaces.length, equalTo(1));

        DtoInterface dtoInterface = interfaces[0];

        assertThat("The DTO interface object is not null",
                   dtoInterface, notNullValue());
        assertThat("The DTO interface object should have a proper id",
                   dtoInterface.getId(), equalTo(interfaceId));
        assertThat("The DtoInterface object should have a proper host id",
                   dtoInterface.getHostId(), equalTo(hostId));
        assertThat("The DtoInterface object should have a proper name",
                   dtoInterface.getName(), equalTo(anInterface.getName()));
        assertThat("The DtoInterface should have a proper MTU valued",
                   dtoInterface.getMtu(), equalTo(anInterface.getMtu()));
        assertThat("The DtoInterface should have the proper mac address",
                   dtoInterface.getMac(), equalTo(new MAC(anInterface.getMac()).toString()));
        assertThat("The DtoInterface type should be returned properly",
                   dtoInterface.getType(), equalTo(DtoInterface.Type.Physical));
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

        DtoHost[] hosts = resource()
            .path("hosts/")
            .type(VendorMediaType.APPLICATION_HOST_COLLECTION_JSON)
            .get(DtoHost[].class);

        assertThat("We should have a proper array of hosts returned",
                   hosts, arrayWithSize(2));
    }

    @Test
    public void testInterfaceUri() throws Exception {
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
        assertThat("the host dto should be properly configured", dtoHostInterface,
                   allOf(notNullValue(), hasProperty("uri", notNullValue())));

    }
}
