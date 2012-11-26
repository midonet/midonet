/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.host;

import com.midokura.midolman.host.state.HostDirectory;
import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.mgmt.VendorMediaType;
import com.midokura.midolman.mgmt.host.rest_api.HostTopology;
import com.midokura.midolman.mgmt.rest_api.DtoWebResource;
import com.midokura.midolman.mgmt.rest_api.FuncTest;
import com.midokura.midolman.mgmt.zookeeper.StaticMockDirectory;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.client.MidonetMgmt;
import com.midokura.midonet.client.dto.*;
import com.midokura.midonet.client.resource.*;
import com.midokura.midonet.client.resource.Host;
import com.midokura.midonet.client.resource.TunnelZone;
import com.midokura.midonet.client.resource.TunnelZoneHost;

import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import junit.framework.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.net.InetAddress;
import java.net.URI;
import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@RunWith(Enclosed.class)
public class TestTunnelZoneHost {

    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";

    public static class TestCrud extends JerseyTest {

        private DtoWebResource dtoResource;
        private HostTopology topologyGre;
        private HostTopology topologyCapwap;
        private HostZkManager hostManager;
        private Directory rootDirectory;

        private UUID host1Id = UUID.randomUUID();
        private UUID host2Id = UUID.randomUUID();

        public TestCrud() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() throws StateAccessException {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);
            rootDirectory = StaticMockDirectory.getDirectoryInstance();
            hostManager = new HostZkManager(rootDirectory, ZK_ROOT_MIDOLMAN);

            DtoHost host1 = new DtoHost();
            host1.setName("host1");
            DtoHost host2 = new DtoHost();
            host2.setName("host2");

            DtoGreTunnelZone tunnelZone1 = new DtoGreTunnelZone();
            tunnelZone1.setName("tz1-name");

            DtoCapwapTunnelZone tunnelZone2 = new DtoCapwapTunnelZone();
            tunnelZone2.setName("tz2-name");

            topologyGre = new HostTopology.Builder(dtoResource, hostManager)
                    .create(host1Id, host1).create("tz1", tunnelZone1).build();

            topologyCapwap = new HostTopology.Builder(dtoResource, hostManager)
                    .create(host2Id, host2).create("tz2", tunnelZone2).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
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
            DtoGreTunnelZone tz1 = topologyGre.getGreTunnelZone("tz1");
            Assert.assertNotNull(tz1);
            testCrud(tz1,
                VendorMediaType.APPLICATION_GRE_TUNNEL_ZONE_HOST_COLLECTION_JSON,
                VendorMediaType.APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON);
        }

        @Test
        public void testCrudCapwap() throws Exception {
            DtoCapwapTunnelZone tz2 = topologyCapwap.getCapwapTunnelZone("tz2");
            Assert.assertNotNull(tz2);
            testCrud(tz2,
                VendorMediaType.APPLICATION_CAPWAP_TUNNEL_ZONE_HOST_COLLECTION_JSON,
                VendorMediaType.APPLICATION_CAPWAP_TUNNEL_ZONE_HOST_JSON);
        }

        @Test
        public void testClientGre() throws Exception {

            URI baseUri = resource().getURI();
            MidonetMgmt mgmt = new MidonetMgmt(baseUri.toString());
            mgmt.enableLogging();

            UUID hostId = UUID.randomUUID();

            HostDirectory.Metadata metadata = new HostDirectory.Metadata();
            metadata.setName("test");
            metadata.setAddresses(new InetAddress[]{
                InetAddress.getByAddress(
                    new byte[]{(byte) 193, (byte) 231, 30, (byte) 197})
            });

            hostManager.createHost(hostId, metadata);
            hostManager.makeAlive(hostId);

            ResourceCollection<Host> hosts = mgmt.getHosts();
            com.midokura.midonet.client.resource.Host host = hosts.get(0);

            TunnelZone greTunnelZone = mgmt.addGreTunnelZone()
                                           .name("gre-tunnel-zone-1")
                                           .create();

            TunnelZoneHost tzHost = greTunnelZone.addTunnelZoneHost()
                                                 .ipAddress("1.1.1.1")
                                                 .hostId(hostId).create();

            assertThat("There is one host entry under the tunnel zone.",
                       greTunnelZone.getHosts().size(), is(1));
        }

        @Test
        public void testClientCapwap() throws Exception {

            URI baseUri = resource().getURI();
            MidonetMgmt mgmt = new MidonetMgmt(baseUri.toString());
            mgmt.enableLogging();

            UUID hostId = UUID.randomUUID();

            HostDirectory.Metadata metadata = new HostDirectory.Metadata();
            metadata.setName("test");
            metadata.setAddresses(new InetAddress[]{
                    InetAddress.getByAddress(
                            new byte[]{(byte) 193, (byte) 231, 30, (byte) 197})
            });

            hostManager.createHost(hostId, metadata);
            hostManager.makeAlive(hostId);

            ResourceCollection<Host> hosts = mgmt.getHosts();
            com.midokura.midonet.client.resource.Host host = hosts.get(0);

            TunnelZone capwapTunnelZone = mgmt.addCapwapTunnelZone()
                    .name("capwap-tunnel-zone-1")
                    .create();

            TunnelZoneHost tzHost = capwapTunnelZone.addTunnelZoneHost()
                    .ipAddress("1.1.1.1")
                    .hostId(hostId).create();

            assertThat("There is one host entry under the tunnel zone.",
                    capwapTunnelZone.getHosts().size(), is(1));
        }
    }

    @RunWith(Parameterized.class)
    public static class TestBadRequestGreTunnelHostCreate extends JerseyTest {

        private HostTopology topology;
        private DtoWebResource dtoResource;
        private final DtoTunnelZoneHost tunnelZoneHost;
        private final String property;
        private HostZkManager hostManager;
        private Directory rootDirectory;

        public TestBadRequestGreTunnelHostCreate(
                DtoTunnelZoneHost tunnelZoneHost, String property) {
            super(FuncTest.appDesc);
            this.tunnelZoneHost = tunnelZoneHost;
            this.property = property;
        }

        @Before
        public void setUp() throws StateAccessException {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);
            rootDirectory = StaticMockDirectory.getDirectoryInstance();
            hostManager = new HostZkManager(rootDirectory, ZK_ROOT_MIDOLMAN);

            DtoGreTunnelZone tunnelZone1 = new DtoGreTunnelZone();
            tunnelZone1.setName("tz1-name");

            topology = new HostTopology.Builder(dtoResource, hostManager)
                    .create("tz1", tunnelZone1).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Parameterized.Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<Object[]>();

            // Invalid host ID
            DtoTunnelZoneHost badHostId = new DtoTunnelZoneHost();
            badHostId.setHostId(UUID.randomUUID()); // non-existent
            params.add(new Object[] { badHostId, "hostId" });

            return params;
        }

        @Test
        public void testBadInputCreate() {
            DtoGreTunnelZone tz = topology.getGreTunnelZone("tz1");

            DtoError error = dtoResource.postAndVerifyBadRequest(
                    tz.getHosts(),
                    VendorMediaType.APPLICATION_GRE_TUNNEL_ZONE_HOST_JSON,
                    tunnelZoneHost);
            List<Map<String, String>> violations = error.getViolations();
            Assert.assertEquals(1, violations.size());
            Assert.assertEquals(property, violations.get(0).get("property"));
        }
    }
}
