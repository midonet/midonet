/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

import org.apache.zookeeper.KeeperException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.midonet.api.VendorMediaType;
import org.midonet.api.host.rest_api.HostTopology;
import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.servlet.JerseyGuiceTestServletContextListener;
import org.midonet.client.MidonetApi;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoHost;
import org.midonet.client.dto.DtoTunnelZone;
import org.midonet.client.dto.DtoTunnelZoneHost;
import org.midonet.client.resource.Host;
import org.midonet.client.resource.HostInterface;
import org.midonet.client.resource.ResourceCollection;
import org.midonet.client.resource.TunnelZone;
import org.midonet.client.resource.TunnelZoneHost;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.MAC;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@RunWith(Enclosed.class)
public class TestTunnelZoneHost {

    public static class TestCrud extends JerseyTest {

        private DtoWebResource dtoResource;
        private HostTopology topologyGre;
        private HostZkManager hostManager;

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

            hostManager = JerseyGuiceTestServletContextListener
                          .getHostZkManager();
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
            dtoResource.postAndVerifyBadRequest(
                    tz.getHosts(),
                    tzhMediaType,
                    mapping);

            // List mapping and verify that there is one
            tzHosts = dtoResource.getAndVerifyOk(
                    tz.getHosts(),
                    tzhCollectionMediaType,
                    DtoTunnelZoneHost[].class);
            Assert.assertEquals(1, tzHosts.length);

            // List the hosts using untyped tunnel zone media type.
            tzHosts = dtoResource.getAndVerifyOk(
                tz.getHosts(),
                VendorMediaType.APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON,
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

            // Now get the single host using the untyped tz-host media type.
            h = dtoResource.getAndVerifyOk(
                UriBuilder.fromUri(tz.getHosts())
                    .path(tzHost.getHostId().toString()).build(),
                VendorMediaType.APPLICATION_TUNNEL_ZONE_HOST_JSON,
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
                VendorMediaType.APPLICATION_TUNNEL_ZONE_HOST_COLLECTION_JSON,
                VendorMediaType.APPLICATION_TUNNEL_ZONE_HOST_JSON);
        }

        @Test
        public void testClientGre() throws Exception {

            URI baseUri = resource().getURI();
            MidonetApi api = new MidonetApi(baseUri.toString());
            api.enableLogging();

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

            DtoError error = dtoResource.postAndVerifyBadRequest(
                    tz.getHosts(),
                    VendorMediaType.APPLICATION_TUNNEL_ZONE_HOST_JSON,
                    tunnelZoneHost);
            List<Map<String, String>> violations = error.getViolations();
            Assert.assertEquals(1, violations.size());
            Assert.assertEquals(property, violations.get(0).get("property"));
        }
    }

    public static class TestBaseUriOverride extends JerseyTest {

        private HostZkManager hostManager;
        private UUID hostId = UUID.randomUUID();

        public TestBaseUriOverride() {
            super(FuncTest.appDescOverrideBaseUri);
        }

        @Before
        public void setUp() throws StateAccessException,
                InterruptedException, KeeperException{
            hostManager = JerseyGuiceTestServletContextListener
                .getHostZkManager();
        }

        @Test
        public void testBaseUriOverride() throws Exception {

            URI baseUri = resource().getURI();
            MidonetApi api = new MidonetApi(baseUri.toString());
            api.enableLogging();

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
            anInterface.setMac(MAC.fromString("16:1f:5c:19:a0:60")
                    .getAddress());
            anInterface.setMtu(123);
            anInterface.setType(HostDirectory.Interface.Type.Physical);
            anInterface.setAddresses(new InetAddress[]{
                    InetAddress.getByAddress(new byte[]{10, 10, 10, 1})
            });

            hostManager.createInterface(hostId, anInterface);

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

            ResourceCollection<HostInterface> hIfaces = host.getInterfaces();

            assertThat("The host should return a proper interfaces object",
                    hIfaces, is(notNullValue()));

            assertThat(hIfaces.size(), equalTo(1));

            HostInterface hIface = hIfaces.get(0);

            // Check URI is overridden correctly
            Assert.assertTrue("Should have correct base URI",
                    hIface.getUri().toString()
                            .startsWith(FuncTest.OVERRIDE_BASE_URI));
        }
    }
}
