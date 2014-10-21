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
package org.midonet.api.host;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.test.framework.JerseyTest;

import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.KeeperException;
import org.codehaus.jackson.type.JavaType;
import org.junit.Before;
import org.junit.Test;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.Topology;
import org.midonet.api.servlet.JerseyGuiceTestServletContextListener;
import org.midonet.client.MidonetApi;
import org.midonet.client.VendorMediaType;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoBridgePort;
import org.midonet.client.dto.DtoHost;
import org.midonet.client.dto.DtoInterface;
import org.midonet.client.dto.DtoPort;
import org.midonet.client.exception.HttpForbiddenException;
import org.midonet.client.resource.Host;
import org.midonet.client.resource.HostInterface;
import org.midonet.client.resource.ResourceCollection;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.packets.MAC;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.fail;
import static org.midonet.client.VendorMediaType.APPLICATION_BRIDGE_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_HOST_COLLECTION_JSON_V3;
import static org.midonet.client.VendorMediaType.APPLICATION_HOST_JSON_V3;
import static org.midonet.client.VendorMediaType.APPLICATION_INTERFACE_COLLECTION_JSON;
import static org.midonet.client.VendorMediaType.APPLICATION_PORT_V2_JSON;

public class TestHost extends JerseyTest {

    public static final int DEFAULT_FLOODING_PROXY_WEIGHT = 1;
    public static final int FLOODING_PROXY_WEIGHT = 42;

    private HostZkManager hostManager;
    private DtoWebResource dtoResource;
    private Topology topology;
    private CuratorFramework curator;
    private MidonetApi api;

    public TestHost() {
        super(FuncTest.appDesc);
    }

    private DtoHost retrieveHostV3(UUID hostId) {
        URI hostUri = ResourceUriBuilder.getHost(
            topology.getApplication().getUri(), hostId);
        DtoHost host = dtoResource.getAndVerifyOk(hostUri,
                                                  APPLICATION_HOST_JSON_V3,
                                                  DtoHost.class);
        return host;
    }

    private List<DtoHost> retrieveHostListV3() throws Exception {
        URI hostListUri = ResourceUriBuilder.getHosts(
            topology.getApplication().getUri());
        String rawHosts = dtoResource.getAndVerifyOk(hostListUri,
               APPLICATION_HOST_COLLECTION_JSON_V3, String.class);
        JavaType type = FuncTest.objectMapper.getTypeFactory()
                                             .constructParametricType(List.class, DtoHost.class);
        return FuncTest.objectMapper.readValue(rawHosts, type);
    }

    private void putHostV3(DtoHost host) {
        putHostV3(host, ClientResponse.Status.OK);
    }

    private void putHostV3(DtoHost host, ClientResponse.Status status) {
        URI hostUri = ResourceUriBuilder.getHost(
            topology.getApplication().getUri(), host.getId());
        dtoResource.putAndVerifyStatus(hostUri,
                                       APPLICATION_HOST_JSON_V3,
                                       host,
                                       status.getStatusCode());
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
        resource().type(VendorMediaType.APPLICATION_JSON_V5)
                .accept(VendorMediaType.APPLICATION_JSON_V5)
                .get(ClientResponse.class);

        topology = new Topology.Builder(dtoResource).build();
        hostManager = JerseyGuiceTestServletContextListener.getHostZkManager();
        curator = JerseyGuiceTestServletContextListener.getCurator();
        URI baseUri = resource().getURI();
        api = new MidonetApi(baseUri.toString());
        api.enableLogging();
    }


    @Test
    public void testNoHosts() throws Exception {
        ClientResponse response = resource()
            .path("hosts/")
            .type(APPLICATION_HOST_COLLECTION_JSON_V3)
            .get(ClientResponse.class);

        assertThat("We should have a proper response",
                   response, is(notNullValue()));

        assertThat(
            "No hosts is not an error situation",
            response.getClientResponseStatus(),
            equalTo(ClientResponse.Status.OK));

        ClientResponse clientResponse = resource()
            .path("hosts/" + UUID.randomUUID().toString())
            .accept(APPLICATION_HOST_JSON_V3).get(ClientResponse.class);

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
    public void testFloodingProxyWeight() throws Exception {
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

        Integer weight = hostManager.getFloodingProxyWeight(hostId);
        assertThat("The flooding proxy weight should be null",
                   weight, is(nullValue()));

        hostManager.setFloodingProxyWeight(hostId, FLOODING_PROXY_WEIGHT);
        weight = hostManager.getFloodingProxyWeight(hostId);
        assertThat("The flooding proxy weight should not be null",
                   weight, is(notNullValue()));
        assertThat("The flooding proxy weight has the proper value",
                   weight, equalTo(FLOODING_PROXY_WEIGHT));

        hostManager.setFloodingProxyWeight(hostId, 0);
        weight = hostManager.getFloodingProxyWeight(hostId);
        assertThat("The flooding proxy weight should not be null",
                   weight, is(notNullValue()));
        assertThat("The flooding proxy weight has the proper value",
                   weight, equalTo(0));
    }

    @Test
    public void testFloodingProxyWeightNoHost() throws Exception {
        UUID hostId = UUID.randomUUID();

        ResourceCollection<Host> hosts = api.getHosts();
        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("Hosts should be empty", hosts.size(), equalTo(0));

        try {
            hostManager.setFloodingProxyWeight(hostId, FLOODING_PROXY_WEIGHT);
            fail(
                "Flooding proxy weight cannot be set on non-existing hosts");
        } catch (NoStatePathException e) {
            // ok
        }

        try {
            hostManager.getFloodingProxyWeight(hostId);
            fail(
                "Flooding proxy weight cannot be retrieved on non-existing hosts");
        } catch (NoStatePathException e) {
            // ok
        }
    }

    @Test
    public void testDeleteFloodingProxyWeight() throws Exception {
        UUID hostId = UUID.randomUUID();

        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("semporiki");

        ResourceCollection<Host> hosts = api.getHosts();
        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("Hosts should be empty", hosts.size(), equalTo(0));
        hostManager.createHost(hostId, metadata);
        hostManager.setFloodingProxyWeight(hostId, FLOODING_PROXY_WEIGHT);

        hosts = api.getHosts();

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.size(), equalTo(1));
        assertThat("The returned host should have the same UUID",
                   hosts.get(0).getId(), equalTo(hostId));

        Integer weight = hostManager.getFloodingProxyWeight(hostId);
        assertThat("The flooding proxy weight should not be null",
                   weight, is(notNullValue()));
        assertThat("The flooding proxy weight has the proper value",
                   weight, equalTo(FLOODING_PROXY_WEIGHT));

        hosts.get(0).delete();

        assertThat("Host should have been removed from ZooKeeper.",
                   hostManager.getHostIds().isEmpty());

        try {
            hostManager.getFloodingProxyWeight(hostId);
            fail("Host flooding proxy weight should be removed from ZK.");
        } catch (NoStatePathException e) {
            // ok
        }
    }

    @Test
    public void testGetFloodingProxyWeightDefault() throws Exception {
        UUID hostId = UUID.randomUUID();
        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("semporiki");
        hostManager.createHost(hostId, metadata);

        DtoHost dtoHost = retrieveHostV3(hostId);
        assertThat("Retrieved host info is not null",
                   dtoHost, is(notNullValue()));
        Integer weight = dtoHost.getFloodingProxyWeight();
        assertThat("Flooding Proxy Weight has the default value",
                   weight, equalTo(DEFAULT_FLOODING_PROXY_WEIGHT));
    }

    @Test
    public void testGetFloodingProxyWeight() throws Exception {
        UUID hostId = UUID.randomUUID();
        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("semporiki");
        hostManager.createHost(hostId, metadata);
        hostManager.setFloodingProxyWeight(hostId, FLOODING_PROXY_WEIGHT);

        DtoHost dtoHost = retrieveHostV3(hostId);
        assertThat("Retrieved host info is not null",
                   dtoHost, is(notNullValue()));
        Integer weight = dtoHost.getFloodingProxyWeight();
        assertThat("Flooding Proxy Weight has the proper value",
                   weight, equalTo(FLOODING_PROXY_WEIGHT));
    }

    @Test
    public void testListHostsWithFloodingProxyWeight() throws Exception {
        UUID hostId = UUID.randomUUID();
        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("semporiki");
        hostManager.createHost(hostId, metadata);
        hostManager.setFloodingProxyWeight(hostId, FLOODING_PROXY_WEIGHT);

        List<DtoHost> hostListV2 = retrieveHostListV3();
        DtoHost dtoHost = hostListV2.iterator().next();
        assertThat("Retrieved host info is not null",
                   dtoHost, is(notNullValue()));
        Integer weight = dtoHost.getFloodingProxyWeight();
        assertThat("Flooding Proxy Weight has the proper value",
                   weight, equalTo(FLOODING_PROXY_WEIGHT));
    }

    @Test
    public void testUpdate() throws Exception {
        UUID hostId = UUID.randomUUID();
        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("semporiki");
        hostManager.createHost(hostId, metadata);
        DtoHost dtoHost = retrieveHostV3(hostId);
        assertThat("Retrieved host info is not null",
                   dtoHost, is(notNullValue()));

        putHostV3(dtoHost);
        Integer weight = hostManager.getFloodingProxyWeight(hostId);
        assertThat("The flooding proxy weight should be the default value",
                   weight, equalTo(DEFAULT_FLOODING_PROXY_WEIGHT));

        dtoHost.setFloodingProxyWeight(FLOODING_PROXY_WEIGHT);
        putHostV3(dtoHost);
        weight = hostManager.getFloodingProxyWeight(hostId);
        assertThat("The flooding proxy weight should be properly set",
                   weight, equalTo(FLOODING_PROXY_WEIGHT));

        dtoHost.setFloodingProxyWeight(null);
        putHostV3(dtoHost, ClientResponse.Status.BAD_REQUEST);
        weight = hostManager.getFloodingProxyWeight(hostId);
        assertThat("The flooding proxy weight should be properly set",
                   weight, equalTo(FLOODING_PROXY_WEIGHT));
    }

    @Test
    public void testUpdateNoHost() throws Exception {
        UUID hostId = UUID.randomUUID();
        HostDirectory.Metadata metadata = new HostDirectory.Metadata();
        metadata.setName("semporiki");
        hostManager.createHost(hostId, metadata);
        DtoHost dtoHost = retrieveHostV3(hostId);
        assertThat("Retrieved host info is not null",
                   dtoHost, is(notNullValue()));
        dtoHost.setId(UUID.randomUUID());

        dtoHost.setFloodingProxyWeight(null);
        putHostV3(dtoHost, ClientResponse.Status.NOT_FOUND);
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
                                          new HostDirectory.VirtualPortMapping(
                                              port.getId(), "BLAH"));

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
                   hostManager.getHostIds(), contains(hostId));

        hostManager.delVirtualPortMapping(hostId, port.getId());
        deadHost.delete();

        assertThat("Host was removed from zk",
                   hostManager.getHostIds(), not(contains(hostId)));
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
                   hostManager.getHostIds(), not(contains(hostId)));
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
                   hostManager.getHostIds(), contains(hostId));
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
            .accept(APPLICATION_HOST_JSON_V3)
            .get(DtoHost.class);

        assertThat(host, is(notNullValue()));
        assertThat(host.getHostInterfaces(), is(notNullValue()));

        ResourceCollection<Host> hosts = api.getHosts();
        Host h = hosts.get(0);
        List<HostInterface> hIfaces = h.getHostInterfaces();

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

        List<HostInterface> hIfaces = host.getHostInterfaces();


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
            .accept(VendorMediaType.APPLICATION_HOST_JSON_V3)
            .get(DtoHost.class);

        DtoInterface[] interfaces = host.getHostInterfaces();

        assertThat("There should be one interface description for the host",
                   interfaces, arrayWithSize(1));

        DtoInterface dtoHostInterface = interfaces[0];
        assertThat("the host dto should be properly configured",
                   dtoHostInterface,
                   allOf(notNullValue(), hasProperty("uri", notNullValue())));

        DtoInterface rereadInterface = resource()
            .uri(dtoHostInterface.getUri())
            .accept(VendorMediaType.APPLICATION_INTERFACE_JSON)
            .get(DtoInterface.class);

        assertThat(
            "The interface should be properly retrieved when requested by uri",
            rereadInterface, notNullValue());

        assertThat(
            "When retrieving by uri we should get the same interface object",
            rereadInterface.getId(), equalTo(dtoHostInterface.getId()));
    }
}
