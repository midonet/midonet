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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;

import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

import org.apache.zookeeper.KeeperException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.midonet.client.MidonetApi;
import org.midonet.client.dto.DtoHost;
import org.midonet.client.dto.DtoTunnelZone;
import org.midonet.client.dto.DtoTunnelZoneHost;
import org.midonet.client.resource.Host;
import org.midonet.client.resource.HostInterface;
import org.midonet.client.resource.ResourceCollection;
import org.midonet.client.resource.TunnelZone;
import org.midonet.client.resource.TunnelZoneHost;
import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.cluster.rest_api.host.rest_api.HostTopology;
import org.midonet.cluster.rest_api.rest_api.DtoWebResource;
import org.midonet.cluster.rest_api.rest_api.FuncTest;
import org.midonet.cluster.rest_api.rest_api.TopologyBackdoor;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.packets.MAC;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TUNNEL_ZONE_HOST_JSON;

@RunWith(Enclosed.class)
public class TestTunnelZoneHost {

    public static class TestCrud extends JerseyTest {

        private DtoWebResource dtoResource;
        private HostTopology topologyGre;

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
            DtoHost host2 = new DtoHost();
            host2.setName("host2");

            DtoTunnelZone tunnelZone1 = new DtoTunnelZone();
            tunnelZone1.setName("tz1-name");

            topologyGre = new HostTopology.Builder(dtoResource)
                    .create(host1Id, host1).create("tz1", tunnelZone1).build();
        }

        private <DTO extends DtoTunnelZone> void testCrud(DTO tz,
                String tzhCollectionMediaType,
                String tzhMediaType) {

            // List mappings.  There should be none.
            DtoTunnelZoneHost[] tzHosts = dtoResource.getAndVerifyOk(
                    tz.getHosts(), tzhCollectionMediaType,
                    DtoTunnelZoneHost[].class);
            Assert.assertEquals(0, tzHosts.length);

            // Map a tunnel zone to a host
            DtoTunnelZoneHost mapping = new DtoTunnelZoneHost();
            mapping.setHostId(host1Id);
            // Verify that forgetting to set the IP address returns bad request
            dtoResource.postAndVerifyBadRequest(
                    tz.getHosts(),
                    tzhMediaType,
                    mapping);
            // Now set the ip address and the create should succeed.
            mapping.setIpAddress("192.168.100.2");
            DtoTunnelZoneHost tzHost = dtoResource.postAndVerifyCreated(
                    tz.getHosts(),
                    tzhMediaType,
                    mapping,
                    DtoTunnelZoneHost.class);
            // Verify that trying to create again fails with a 400 error.
            dtoResource.postAndVerifyStatus(
                    tz.getHosts(),
                    tzhMediaType,
                    mapping,
                    Response.Status.CONFLICT.getStatusCode());

            // List mapping and verify that there is one
            tzHosts = dtoResource.getAndVerifyOk(
                    tz.getHosts(),
                    tzhCollectionMediaType,
                    DtoTunnelZoneHost[].class);
            Assert.assertEquals(1, tzHosts.length);
            // MNA-921: ensure that the API is filling the tz id and URI in the
            // TunnelZoneHost DTOs.
            Assert.assertEquals(tz.getId(), tzHosts[0].getTunnelZoneId());
            Assert.assertNotNull(tzHosts[0].getUri());

            // Getting the tzhost object directly should also bring the URI
            // and tunnel zone id
            tzHost = dtoResource.getAndVerifyOk(tzHosts[0].getUri(),
                                                tzhMediaType,
                                                DtoTunnelZoneHost.class);
            Assert.assertEquals(tzHosts[0].getUri(), tzHost.getUri());
            Assert.assertEquals(tz.getId(), tzHost.getTunnelZoneId());

            // List the hosts using untyped tunnel zone media type.
            tzHosts = dtoResource.getAndVerifyOk(
                tz.getHosts(),
                APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON(),
                DtoTunnelZoneHost[].class);
            Assert.assertEquals(1, tzHosts.length);

            // Get the single host using the specific media type.
            DtoTunnelZoneHost h = dtoResource.getAndVerifyOk(
                UriBuilder.fromUri(tz.getHosts())
                    .path(tzHost.getHostId().toString()).build(),
                tzhMediaType,
                DtoTunnelZoneHost.class);
            Assert.assertEquals(tzHost.getIpAddress(), h.getIpAddress());
            Assert.assertEquals(tzHost.getTunnelZoneId(), h.getTunnelZoneId());
            Assert.assertNotNull(tzHost.getUri());

            // Now get the single host using the untyped tz-host media type.
            h = dtoResource.getAndVerifyOk(
                UriBuilder.fromUri(tz.getHosts())
                    .path(tzHost.getHostId().toString()).build(),
                APPLICATION_TUNNEL_ZONE_HOST_JSON(),
                DtoTunnelZoneHost.class);
            Assert.assertEquals(tzHost.getIpAddress(), h.getIpAddress());
            Assert.assertEquals(tzHost.getTunnelZoneId(), h.getTunnelZoneId());

            // Remove mapping
            dtoResource.deleteAndVerifyNoContent(tzHost.getUri(), tzhMediaType);

            // List mapping and verify that there is none
            tzHosts = dtoResource.getAndVerifyOk(
                    tz.getHosts(),
                    tzhCollectionMediaType,
                    DtoTunnelZoneHost[].class);
            Assert.assertEquals(0, tzHosts.length);
        }

        @Test
        public void testCrudGre() throws Exception {
            DtoTunnelZone tz1 = topologyGre.getGreTunnelZone("tz1");
            Assert.assertNotNull(tz1);
            testCrud(tz1,
                APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON(),
                APPLICATION_TUNNEL_ZONE_HOST_JSON());
        }

        @Test
        public void testClientGre() throws Exception {

            URI baseUri = resource().getURI();
            MidonetApi api = new MidonetApi(baseUri.toString());
            api.enableLogging();

            UUID hostId = UUID.randomUUID();

            TopologyBackdoor tb = FuncTest._injector
                                          .getInstance(TopologyBackdoor.class);
            tb.createHost(hostId, "test", new InetAddress[]{
                InetAddress.getByAddress(
                    new byte[]{(byte) 193, (byte) 231, 30, (byte) 197})
            });
            tb.makeHostAlive(hostId);

            api.getHosts().get(0);

            TunnelZone greTunnelZone = api.addGreTunnelZone()
                                           .name("gre-tunnel-zone-1")
                                           .create();

            greTunnelZone.addTunnelZoneHost()
                         .ipAddress("1.1.1.1")
                         .hostId(hostId).create();

            assertThat("There is one host entry under the tunnel zone.",
                       greTunnelZone.getHosts().size(), is(1));
        }
    }

    @RunWith(Parameterized.class)
    public static class TestBadRequestTunnelHostCreate extends JerseyTest {

        private HostTopology topology;
        private DtoWebResource dtoResource;
        private final DtoTunnelZoneHost tunnelZoneHost;
        private final String property;

        public TestBadRequestTunnelHostCreate(
                DtoTunnelZoneHost tunnelZoneHost, String property) {
            super(FuncTest.appDesc);
            this.tunnelZoneHost = tunnelZoneHost;
            this.property = property;
        }

        @Before
        public void setUp() throws StateAccessException,
                KeeperException, InterruptedException, SerializationException {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            DtoTunnelZone tunnelZone1 = new DtoTunnelZone();
            tunnelZone1.setName("tz1-name");

            topology = new HostTopology.Builder(dtoResource)
                    .create("tz1", tunnelZone1).build();
        }

        @Parameterized.Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<>();

            // Invalid host ID
            DtoTunnelZoneHost badHostId = new DtoTunnelZoneHost();
            badHostId.setHostId(UUID.randomUUID()); // non-existent
            badHostId.setIpAddress("10.10.10.10");
            params.add(new Object[] { badHostId, "hostId" });

            return params;
        }

        @Test
        public void testBadInputCreate() {
            DtoTunnelZone tz = topology.getGreTunnelZone("tz1");
            dtoResource.postAndVerifyError(
                tz.getHosts(), APPLICATION_TUNNEL_ZONE_HOST_JSON(),
                tunnelZoneHost, Response.Status.NOT_FOUND);
        }
    }

    public static class TestBaseUriOverride extends JerseyTest {

        private UUID hostId = UUID.randomUUID();

        public TestBaseUriOverride() {
            super(FuncTest.appDescOverrideBaseUri);
        }

        @Test
        @Ignore("TODO FIXME - pending implementation in v2")
        public void testBaseUriOverride() throws Exception {
            URI baseUri = resource().getURI();
            MidonetApi api = new MidonetApi(baseUri.toString());
            api.enableLogging();

            TopologyBackdoor tb = FuncTest._injector
                .getInstance(TopologyBackdoor.class);
            tb.createHost(hostId, "test", new InetAddress[]{
                InetAddress.getByAddress(
                    new byte[]{(byte) 193, (byte) 231, 30, (byte) 197})
            });
            tb.makeHostAlive(hostId);

            MAC mac = MAC.fromString("16:1f:5c:19:a0:60");
            InetAddress[] addrs = new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{10, 10, 10, 1})
            };
            tb.createInterface(hostId, "eth0", mac, 123, addrs);

            ResourceCollection<Host> hosts = api.getHosts();
            org.midonet.client.resource.Host host = hosts.get(0);

            TunnelZone greTunnelZone = api.addGreTunnelZone()
                    .name("gre-tunnel-zone-1")
                    .create();

            // Check tunnel zone URI is overridden correctly
            URI tzUri = greTunnelZone.getUri();
            Assert.assertTrue("Should have correct base URI",
                    tzUri.toString().startsWith(FuncTest.OVERRIDE_BASE_URI));

            TunnelZoneHost tzHost = greTunnelZone.addTunnelZoneHost()
                    .ipAddress("1.1.1.1")
                    .hostId(hostId).create();

            assertThat("There is one host entry under the tunnel zone.",
                    greTunnelZone.getHosts().size(), is(1));

            // Check tunnel zone host URI is overridden correctly
            URI tzHostUri = tzHost.getUri();
            Assert.assertTrue("Should have correct base URI",
                    tzHostUri.toString()
                            .startsWith(FuncTest.OVERRIDE_BASE_URI));

            List<HostInterface> hIfaces = host.getHostInterfaces();

            assertThat(hIfaces.size(), equalTo(1));

            HostInterface hIface = hIfaces.get(0);

            // Check URI is overridden correctly
            Assert.assertTrue("Should have correct base URI",
                    hIface.getUri().toString()
                            .startsWith(FuncTest.OVERRIDE_BASE_URI));
        }
    }
}
