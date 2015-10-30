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
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JavaType;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.test.framework.JerseyTest;

import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;

import org.midonet.client.MidonetApi;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoBridgePort;
import org.midonet.client.dto.DtoHost;
import org.midonet.client.dto.DtoInterface;
import org.midonet.client.dto.DtoPort;
import org.midonet.client.dto.DtoTunnelZone;
import org.midonet.client.dto.DtoTunnelZoneHost;
import org.midonet.client.dto.TunnelZoneType;
import org.midonet.client.resource.Host;
import org.midonet.client.resource.HostInterface;
import org.midonet.client.resource.ResourceCollection;
import org.midonet.cluster.rest_api.ForbiddenHttpException;
import org.midonet.cluster.rest_api.ResourceUriBuilder;
import org.midonet.cluster.rest_api.rest_api.DtoWebResource;
import org.midonet.cluster.rest_api.rest_api.FuncTest;
import org.midonet.cluster.rest_api.rest_api.Topology;
import org.midonet.cluster.rest_api.rest_api.TopologyBackdoor;
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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_BRIDGE_JSON_V4;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_HOST_COLLECTION_JSON_V3;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_HOST_JSON_V3;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_INTERFACE_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_JSON_V5;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_PORT_V3_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TUNNEL_ZONE_HOST_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TUNNEL_ZONE_JSON;
import static org.midonet.cluster.util.UUIDUtil.fromProto;

public class TestHost extends JerseyTest {

    public static final int DEFAULT_FLOODING_PROXY_WEIGHT = 1;
    public static final int FLOODING_PROXY_WEIGHT = 42;

    private TopologyBackdoor topologyBackdoor;
    private DtoWebResource dtoResource;
    private Topology topology;
    private MidonetApi api;

    public TestHost() {
        super(FuncTest.appDesc);
    }

    private DtoHost retrieveHostV3(UUID hostId) {
        URI hostUri = ResourceUriBuilder.getHost(
            topology.getApplication().getUri(), hostId);
        return dtoResource.getAndVerifyOk(hostUri, APPLICATION_HOST_JSON_V3(),
                                          DtoHost.class);
    }

    private List<DtoHost> retrieveHostListV3() throws Exception {
        URI hostListUri = ResourceUriBuilder.getHosts(
            topology.getApplication().getUri());
        String rawHosts = dtoResource.getAndVerifyOk(hostListUri,
               APPLICATION_HOST_COLLECTION_JSON_V3(), String.class);
        JavaType type = FuncTest.objectMapper
            .getTypeFactory().constructParametrizedType(List.class, List.class,
                                                        DtoHost.class);
        return FuncTest.objectMapper.readValue(rawHosts, type);
    }

    private void putHostV3(DtoHost host) {
        putHostV3(host, ClientResponse.Status.OK);
    }

    private void putHostV3(DtoHost host, ClientResponse.Status status) {
        URI hostUri = ResourceUriBuilder.getHost(
            topology.getApplication().getUri(), host.getId());
        dtoResource.putAndVerifyStatus(hostUri,
                                       APPLICATION_HOST_JSON_V3(),
                                       host,
                                       status.getStatusCode());
    }

    private DtoBridge addBridge(String bridgeName) {
        DtoBridge bridge = new DtoBridge();
        bridge.setName(bridgeName);
        bridge.setTenantId("tenant1");
        bridge = dtoResource.postAndVerifyCreated(
            topology.getApplication().getBridges(),
            APPLICATION_BRIDGE_JSON_V4(), bridge, DtoBridge.class);
        return bridge;
    }

    private DtoBridgePort addPort(DtoBridge bridge) {
        DtoBridgePort port = new DtoBridgePort();
        port = dtoResource.postAndVerifyCreated(bridge.getPorts(),
            APPLICATION_PORT_V3_JSON(), port, DtoBridgePort.class);
        return port;
    }

    // This one also tests Create with given tenant ID string
    @Before
    public void before() throws KeeperException, InterruptedException {
        dtoResource = new DtoWebResource(resource());
        resource().type(APPLICATION_JSON_V5())
                .accept(APPLICATION_JSON_V5())
                .get(ClientResponse.class);

        topology = new Topology.Builder(dtoResource).build();
        topologyBackdoor = FuncTest._injector
                                   .getInstance(TopologyBackdoor.class);
        URI baseUri = resource().getURI();
        api = new MidonetApi(baseUri.toString());
        api.enableLogging();
    }


    @Test
    public void testNoHosts() throws Exception {
        ClientResponse response = resource()
            .path("hosts/")
            .accept(APPLICATION_HOST_COLLECTION_JSON_V3())
            .get(ClientResponse.class);

        assertThat("We should have a proper response",
                   response, is(notNullValue()));

        assertThat(
            "No hosts is not an error situation",
            response.getStatusInfo().getStatusCode(),
            equalTo(ClientResponse.Status.OK.getStatusCode()));

        ClientResponse clientResponse = resource()
            .path("hosts/" + UUID.randomUUID().toString())
            .accept(APPLICATION_HOST_JSON_V3()).get(ClientResponse.class);

        assertThat(clientResponse.getStatusInfo().getStatusCode(),
                   equalTo(ClientResponse.Status.NOT_FOUND.getStatusCode()));
    }

    @Test
    public void testAliveHost() throws Exception {
        UUID hostId = UUID.fromString(topologyBackdoor.getNamespace());

        ResourceCollection<Host> hosts = api.getHosts();
        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("Hosts should be empty", hosts.size(), equalTo(0));
        topologyBackdoor.createHost(hostId, "semporiki", new InetAddress[]{});

        hosts = api.getHosts();

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.size(), equalTo(1));
        assertThat("The returned host should have the same UUID",
                   hosts.get(0).getId(), equalTo(hostId));
        assertThat("The host should not be alive",
                   hosts.get(0).isAlive(), equalTo(false));

        topologyBackdoor.makeHostAlive(hostId);

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

        ResourceCollection<Host> hosts = api.getHosts();
        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("Hosts should be empty", hosts.size(), equalTo(0));
        topologyBackdoor.createHost(hostId, "semporiki", new InetAddress[]{});

        hosts = api.getHosts();

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertEquals("We should expose 1 host via the API",
                     hosts.size(), 1);
        assertEquals("The returned host should have the same UUID",
                     hosts.get(0).getId(), hostId);

        int weight = hosts.get(0).getFloodingProxyWeight();
        assertEquals("The flooding proxy weight should be the default value",
                     weight, DEFAULT_FLOODING_PROXY_WEIGHT);

        topologyBackdoor.setFloodingProxyWeight(hostId, FLOODING_PROXY_WEIGHT);
        weight = topologyBackdoor.getFloodingProxyWeight(hostId);
        assertThat("The flooding proxy weight should not be null",
                   weight, is(notNullValue()));
        assertThat("The flooding proxy weight has the proper value",
                   weight, equalTo(FLOODING_PROXY_WEIGHT));

        topologyBackdoor.setFloodingProxyWeight(hostId, 1);
        weight = topologyBackdoor.getFloodingProxyWeight(hostId);
        assertThat("The flooding proxy weight should not be null",
                   weight, is(notNullValue()));
        assertThat("The flooding proxy weight has the proper value",
                   weight, equalTo(1));
    }

    @Test
    public void testFloodingProxyWeightNoHost() throws Exception {
        UUID hostId = UUID.randomUUID();

        ResourceCollection<Host> hosts = api.getHosts();
        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("Hosts should be empty", hosts.size(), equalTo(0));

        try {
            topologyBackdoor.setFloodingProxyWeight(hostId, FLOODING_PROXY_WEIGHT);
            fail(
                "Flooding proxy weight cannot be set on non-existing hosts");
        } catch (RuntimeException e) {
            // ok
        }

        try {
            topologyBackdoor.getFloodingProxyWeight(hostId);
            fail("Flooding proxy weight cannot be retrieved on non-existing hosts");
        } catch (RuntimeException e) {
            // ok
        }
    }

    @Test
    public void testDeleteFloodingProxyWeight() throws Exception {
        UUID hostId = UUID.randomUUID();

        ResourceCollection<Host> hosts = api.getHosts();
        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("Hosts should be empty", hosts.size(), equalTo(0));
        topologyBackdoor.createHost(hostId, "semporiki", new InetAddress[]{});
        topologyBackdoor.setFloodingProxyWeight(hostId, FLOODING_PROXY_WEIGHT);

        hosts = api.getHosts();

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.size(), equalTo(1));
        assertThat("The returned host should have the same UUID",
                   hosts.get(0).getId(), equalTo(hostId));

        Integer weight = topologyBackdoor.getFloodingProxyWeight(hostId);
        assertThat("The flooding proxy weight should not be null",
                   weight, is(notNullValue()));
        assertThat("The flooding proxy weight has the proper value",
                   weight, equalTo(FLOODING_PROXY_WEIGHT));

        hosts.get(0).delete();

        assertThat("Host should have been removed from ZooKeeper.",
                   topologyBackdoor.getHostIds().isEmpty());

        try {
            topologyBackdoor.getFloodingProxyWeight(hostId);
            fail("Host flooding proxy weight should be removed from ZK.");
        } catch (RuntimeException e) {
            // ok
        }
    }

    @Test
    public void testGetFloodingProxyWeightDefault() throws Exception {
        UUID hostId = UUID.randomUUID();
        topologyBackdoor.createHost(hostId, "semporiki", new InetAddress[]{});

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
        topologyBackdoor.createHost(hostId, "semporiki", new InetAddress[]{});
        topologyBackdoor.setFloodingProxyWeight(hostId, FLOODING_PROXY_WEIGHT);

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
        topologyBackdoor.createHost(hostId, "semporiki", new InetAddress[]{});
        topologyBackdoor.setFloodingProxyWeight(hostId, FLOODING_PROXY_WEIGHT);

        List<DtoHost> hostListV2 = retrieveHostListV3();
        DtoHost dtoHost = hostListV2.iterator().next();
        assertThat("Retrieved host info is not null",
                   dtoHost, is(notNullValue()));
        Integer weight = dtoHost.getFloodingProxyWeight();
        assertThat("Flooding Proxy Weight has the proper value",
                   weight, equalTo(FLOODING_PROXY_WEIGHT));
    }

    @Test
    public void testUpdates() throws Exception {
        UUID hostId = UUID.randomUUID();
        String name = "semporiki";
        topologyBackdoor.createHost(hostId, name, new InetAddress[]{});
        DtoHost dtoHost = retrieveHostV3(hostId);
        assertThat("Retrieved host info is not null",
                   dtoHost, is(notNullValue()));

        // Add the host to a tunnel zone
        DtoTunnelZone tz = new DtoTunnelZone();
        tz.setName("test");
        tz.setType(TunnelZoneType.GRE);
        URI tzUri = topology.getApplication().getTunnelZones();
        tz = dtoResource.postAndVerifyCreated(tzUri,
                                              APPLICATION_TUNNEL_ZONE_JSON(),
                                              tz, DtoTunnelZone.class);

        DtoTunnelZoneHost tzh = new DtoTunnelZoneHost();
        tzh.setHostId(hostId);
        tzh.setIpAddress("127.0.0.1");
        tzh.setTunnelZoneId(tz.getId());
        dtoResource.postAndVerifyCreated(tz.getHosts(),
                                         APPLICATION_TUNNEL_ZONE_HOST_JSON(),
                                         tzh, DtoTunnelZoneHost.class);

        dtoHost.setName(name);

        putHostV3(dtoHost);

        dtoHost = retrieveHostV3(hostId);
        assertEquals("The name is NOT changed", dtoHost.getName(), name);

        // The update should NOT overwrite the tz memberships, we have
        // to look straight in storage because the backrefs to the
        // tunnel zone are not in the API
        org.midonet.cluster.models.Topology.Host zoomHost =
            topologyBackdoor.getHost(hostId);

        assertEquals(1, zoomHost.getTunnelZoneIdsCount());
        assertEquals(tz.getId(), fromProto(zoomHost.getTunnelZoneIds(0)));

        Integer weight = topologyBackdoor.getFloodingProxyWeight(hostId);
        assertThat("The flooding proxy weight should be the default value",
                   weight, equalTo(DEFAULT_FLOODING_PROXY_WEIGHT));

        dtoHost.setFloodingProxyWeight(FLOODING_PROXY_WEIGHT);
        putHostV3(dtoHost);
        weight = topologyBackdoor.getFloodingProxyWeight(hostId);
        assertThat("The flooding proxy weight should be properly set",
                   weight, equalTo(FLOODING_PROXY_WEIGHT));

        dtoHost.setFloodingProxyWeight(null);
        putHostV3(dtoHost, ClientResponse.Status.BAD_REQUEST);
        weight = topologyBackdoor.getFloodingProxyWeight(hostId);
        assertThat("The flooding proxy weight should be properly set",
                   weight, equalTo(FLOODING_PROXY_WEIGHT));
    }

    @Test
    public void testUpdateNoHost() throws Exception {
        UUID hostId = UUID.randomUUID();
        topologyBackdoor.createHost(hostId, "semporiki", new InetAddress[]{});
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

        topologyBackdoor.createHost(hostId, "testDead", new InetAddress[]{});
        // Don't make this host alive. We are testing deleting while dead.

        DtoBridge bridge = addBridge("testBridge");
        DtoPort port = addPort(bridge);
        topologyBackdoor.addVirtualPortMapping(hostId, port.getId(), "BLAH");

        ResourceCollection<Host> hosts = api.getHosts();
        Host deadHost = hosts.get(0);
        boolean caught403 = false;
        try {
            deadHost.delete();
        } catch (ForbiddenHttpException ex) {
            caught403 = true;
        }
        assertThat("Deletion of host got 403", caught403, is(true));
        assertThat("Host was not removed from zk",
                   topologyBackdoor.getHostIds(), contains(hostId));

        topologyBackdoor.delVirtualPortMapping(hostId, port.getId());
        deadHost.delete();

        assertThat("Host was removed from zk",
                   topologyBackdoor.getHostIds(), not(contains(hostId)));
    }

    @Test
    public void testOneHostNoAddresses() throws Exception {
        UUID hostId = UUID.randomUUID();

        topologyBackdoor.createHost(hostId, "testhost", new InetAddress[]{});

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

        String hostName = "testhost";
        InetAddress[] addrs = new InetAddress[]{
            InetAddress.getByAddress(new byte[]{127, 0, 0, 1}),
            InetAddress.getByAddress(
                new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 127, 0, 0, 1}),
        };
        topologyBackdoor.createHost(hostId, hostName, addrs);

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
            host.getName(), equalTo(hostName));
        assertThat("The returned host should have a not null address list",
                   host.getAddresses(), not(nullValue()));

        assertFalse("The host should not be alive", host.isAlive());
    }

    @Test
    public void testDeleteDeadHost() throws Exception {
        UUID hostId = UUID.randomUUID();
        String hostName = "testhost";
        InetAddress[] addrs = new InetAddress[]{
            InetAddress.getByAddress(new byte[]{127, 0, 0, 1}),
        };
        topologyBackdoor.createHost(hostId, hostName, addrs);

        // Add the host to a tunnel zone
        DtoTunnelZone tz = new DtoTunnelZone();
        tz.setName("test");
        tz.setType(TunnelZoneType.GRE);
        URI tzUri = topology.getApplication().getTunnelZones();
        tz = dtoResource.postAndVerifyCreated(tzUri,
                                              APPLICATION_TUNNEL_ZONE_JSON(),
                                              tz, DtoTunnelZone.class);

        DtoTunnelZoneHost tzh = new DtoTunnelZoneHost();
        tzh.setHostId(hostId);
        tzh.setIpAddress("127.0.0.1");
        tzh.setTunnelZoneId(tz.getId());
        dtoResource.postAndVerifyCreated(tz.getHosts(),
                                         APPLICATION_TUNNEL_ZONE_HOST_JSON(),
                                         tzh, DtoTunnelZoneHost.class);

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

        //NOTE: right now client library doesn't store http response status.
        //       maybe store the most recent response object in resource object.
        hosts.get(0).delete();

        assertThat("Host should have been removed from ZooKeeper.",
                   topologyBackdoor.getHostIds(), not(contains(hostId)));

        DtoTunnelZoneHost[] tzhs = dtoResource.getAndVerifyOk(tz.getHosts(),
                              APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON(),
                              DtoTunnelZoneHost[].class);

        assertEquals("The tunnel zone membership was removed", 0, tzhs.length);
    }

    @Test
    public void testDeleteAliveHost() throws Exception {
        UUID hostId = UUID.fromString(topologyBackdoor.getNamespace());

        topologyBackdoor.createHost(hostId, "semporiki", new InetAddress[]{});
        topologyBackdoor.makeHostAlive(hostId);

        ResourceCollection<Host> hosts = api.getHosts();

        assertThat("Hosts array should not be null", hosts, is(notNullValue()));
        assertThat("We should expose 1 host via the API",
                   hosts.size(), equalTo(1));

        Host h1 = hosts.get(0);
        assertEquals(
            "The returned host should have the same UUID as the one in ZK",
            h1.getId(), hostId);
        assertTrue("The returned host should be alive", h1.isAlive());


        boolean caught403 = false;
        try {
            h1.delete();
        } catch (ForbiddenHttpException ex) {
            caught403 = true;
        }
        assertThat("Deletion of host got 403", caught403, is(true));
        assertThat("Host was not removed from zk",
                   topologyBackdoor.getHostIds(), contains(hostId));
    }

    @Test
    public void testHostWithoutInterface() throws Exception {
        UUID hostId = UUID.fromString(topologyBackdoor.getNamespace());

        topologyBackdoor.createHost(hostId, "test", new InetAddress[]{
            InetAddress.getByAddress(
                new byte[]{(byte) 193, (byte) 231, 30, (byte) 197})
        });
        topologyBackdoor.makeHostAlive(hostId);

        DtoHost host = resource()
            .path("hosts/" + hostId.toString())
            .accept(APPLICATION_HOST_JSON_V3())
            .get(DtoHost.class);

        assertNotNull(host);
        assertNotNull(host.getHostInterfaces());

        ResourceCollection<Host> hosts = api.getHosts();
        Host h = hosts.get(0);
        List<HostInterface> hIfaces = h.getHostInterfaces();

        assertEquals("Host doesn't have any interfaces", hIfaces.size(), 0);

    }

    @Test
    public void testHostWithOneInterface() throws Exception {
        UUID hostId = UUID.fromString(topologyBackdoor.getNamespace());

        topologyBackdoor.createHost(hostId, "semporiki", new InetAddress[]{
            InetAddress.getByAddress(
                new byte[]{(byte) 193, (byte) 231, 30, (byte) 197})
        });
        topologyBackdoor.makeHostAlive(hostId);

        String name = "eth0";
        int mtu = 213;
        MAC mac = MAC.fromString("16:1f:5c:19:a0:60");
        topologyBackdoor.createInterface(hostId, name, mac, mtu,
            new InetAddress[]{InetAddress.getByAddress(new byte[]{10,10,10,1})
        });

        ResourceCollection<Host> hosts = api.getHosts();
        Host host = hosts.get(0);

        List<HostInterface> ifs = host.getHostInterfaces();


        assertNotNull("The host should return a proper interfaces object", ifs);

        assertEquals(ifs.size(), 1);

        HostInterface hIface = ifs.get(0);

        assertEquals("The DtoInterface object should have a proper host id",
                     hIface.getHostId(), hostId);
        assertEquals("The DtoInterface object should have a proper name",
                     hIface.getName(), name);
        assertEquals("The DtoInterface should have a proper MTU valued",
                     hIface.getMtu(), mtu);
        assertEquals("The DtoInterface should have the proper mac address",
                     hIface.getMac(), mac.toString());
        assertEquals("The DtoInterface type should be returned properly",
                     hIface.getType(), DtoInterface.Type.Physical);
    }

    @Test
    public void testTwoHosts() throws Exception {
        UUID host1 = UUID.randomUUID();
        UUID host2 = UUID.randomUUID();

        topologyBackdoor.createHost(host1, "host1", new InetAddress[]{});
        topologyBackdoor.createHost(host2, "host2", new InetAddress[]{});

        ResourceCollection<Host> hosts = api.getHosts();

        assertThat("We should have a proper array of hosts returned",
                   hosts.size(), equalTo(2));
    }

    @Test
    public void testInterfaceUriIsValid() throws Exception {
        UUID hostId = UUID.fromString(topologyBackdoor.getNamespace());

        topologyBackdoor.createHost(hostId, "host1", new InetAddress[]{});

        String name = "eth0";
        int mtu = 213;
        MAC mac = MAC.fromString("16:1f:5c:19:a0:60");
        topologyBackdoor.createInterface(hostId, name, mac, mtu,
                                         new InetAddress[]{
                                             InetAddress.getByAddress(
                                                 new byte[]{10, 10, 10, 1})
                                         });

        DtoHost host = resource()
            .path("hosts/" + hostId.toString())
            .accept(APPLICATION_HOST_JSON_V3())
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
            .accept(APPLICATION_INTERFACE_JSON())
            .get(DtoInterface.class);

        assertNotNull(
            "The interface should be properly retrieved when requested by uri",
            rereadInterface);

        assertEquals(
            "When retrieving by uri we should get the same interface object",
            rereadInterface.getId(), dtoHostInterface.getId());
    }
}
