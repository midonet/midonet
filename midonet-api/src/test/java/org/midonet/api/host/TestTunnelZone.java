/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host;

import org.midonet.api.VendorMediaType;
import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.api.host.rest_api.HostTopology;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.client.MidonetApi;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoGreTunnelZone;
import org.midonet.client.dto.DtoTunnelZone;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import junit.framework.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import java.net.URI;
import java.util.UUID;
import javax.ws.rs.core.UriBuilder;

@RunWith(Enclosed.class)
public class TestTunnelZone {

    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";

    public static class TestCrud extends JerseyTest {

        private DtoWebResource dtoResource;
        private HostTopology topology;
        private HostZkManager hostManager;
        private Directory rootDirectory;
        private MidonetApi api;

        private UUID host1Id = UUID.randomUUID();

        public TestCrud() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() throws StateAccessException {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);
            rootDirectory = StaticMockDirectory.getDirectoryInstance();
            hostManager = new HostZkManager(rootDirectory, ZK_ROOT_MIDOLMAN);

            topology = new HostTopology.Builder(dtoResource, hostManager)
                    .build();

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

            DtoApplication app = topology.getApplication();
            URI tunnelZonesUri = app.getTunnelZones();

            // Get tunnel zones and verify there is none
            DtoGreTunnelZone tunnelZone = new DtoGreTunnelZone();
            tunnelZone.setName("tz1-name");
            tunnelZone = dtoResource.postAndVerifyCreated(tunnelZonesUri,
                    VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON, tunnelZone,
                    DtoGreTunnelZone.class);
            Assert.assertNotNull(tunnelZone.getId());
            Assert.assertEquals("tz1-name", tunnelZone.getName());

            // Update tunnel zone name
            tunnelZone.setName("tz1-name-updated");
            tunnelZone = dtoResource.putAndVerifyNoContent(tunnelZone.getUri(),
                    VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON, tunnelZone,
                    DtoGreTunnelZone.class);
            Assert.assertEquals("tz1-name-updated", tunnelZone.getName());

            // List and make sure that there is one
            DtoTunnelZone[] tunnelZones = dtoResource.getAndVerifyOk(
                    tunnelZonesUri,
                    VendorMediaType.APPLICATION_TUNNEL_ZONE_COLLECTION_JSON,
                    DtoTunnelZone[].class);
            Assert.assertEquals(1, tunnelZones.length);

            // Get the tunnel zone building the URI by hand.
            DtoGreTunnelZone tZone = dtoResource.getAndVerifyOk(
                    UriBuilder.fromUri(tunnelZonesUri)
                        .path(tunnelZone.getId().toString()).build(),
                    VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON,
                    DtoGreTunnelZone.class);
            Assert.assertEquals(tunnelZone.getType(), tZone.getType());
            Assert.assertEquals(tunnelZone.getName(), tZone.getName());

            // Getting a non-existent zone returns a 404.
            dtoResource.getAndVerifyNotFound(
                UriBuilder.fromUri(tunnelZonesUri)
                    .path(UUID.randomUUID().toString()).build(),
                VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON);

            // Delete it
            dtoResource.deleteAndVerifyNoContent(tunnelZone.getUri(),
                    VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON);

            // list and make sure it's gone
            tunnelZones = dtoResource.getAndVerifyOk(
                    tunnelZonesUri,
                    VendorMediaType.APPLICATION_TUNNEL_ZONE_COLLECTION_JSON,
                    DtoTunnelZone[].class);
            Assert.assertEquals(0, tunnelZones.length);

        }
    }
}
