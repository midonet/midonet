/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.host;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

import org.apache.zookeeper.KeeperException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import org.midonet.api.VendorMediaType;
import org.midonet.api.host.rest_api.HostTopology;
import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.RestApiTestBase;
import org.midonet.api.serialization.SerializationModule;
import org.midonet.api.vtep.VtepDataClientProvider;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.MidonetApi;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoTunnelZone;
import org.midonet.client.dto.DtoVtep;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.version.guice.VersionModule;

@RunWith(Enclosed.class)
public class TestTunnelZone {

    public static final String ZK_ROOT_MIDOLMAN = "/test/midolman";

    public static class TestCrud extends RestApiTestBase {

        private DtoWebResource dtoResource;
        private HostTopology topology;
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
                return StaticMockDirectory.getDirectoryInstance();
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
            DtoTunnelZone tunnelZone = new DtoTunnelZone();
            tunnelZone.setName("tz1-name");
            tunnelZone = dtoResource.postAndVerifyCreated(tunnelZonesUri,
                    VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON, tunnelZone,
                    DtoTunnelZone.class);
            Assert.assertNotNull(tunnelZone.getId());
            Assert.assertEquals("tz1-name", tunnelZone.getName());

            // Do not allow duplicates
            DtoTunnelZone tunnelZone2 = new DtoTunnelZone();
            tunnelZone2.setName("tz1-name");
            DtoError error = dtoResource.postAndVerifyBadRequest(tunnelZonesUri,
                VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON, tunnelZone2);
            Assert.assertEquals(1, error.getViolations().size());

            // There should only be one
            DtoTunnelZone[] tunnelZones = dtoResource.getAndVerifyOk(
                tunnelZonesUri,
                VendorMediaType.APPLICATION_TUNNEL_ZONE_COLLECTION_JSON,
                DtoTunnelZone[].class);
            Assert.assertEquals(1, tunnelZones.length);

            // Update tunnel zone name
            tunnelZone.setName("tz1-name-updated");
            tunnelZone = dtoResource.putAndVerifyNoContent(tunnelZone.getUri(),
                    VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON, tunnelZone,
                    DtoTunnelZone.class);
            Assert.assertEquals("tz1-name-updated", tunnelZone.getName());

            // List and make sure that there is one
            tunnelZones = dtoResource.getAndVerifyOk(
                    tunnelZonesUri,
                    VendorMediaType.APPLICATION_TUNNEL_ZONE_COLLECTION_JSON,
                    DtoTunnelZone[].class);
            Assert.assertEquals(1, tunnelZones.length);

            // Get the tunnel zone building the URI by hand.
            DtoTunnelZone tZone = dtoResource.getAndVerifyOk(
                    UriBuilder.fromUri(tunnelZonesUri)
                        .path(tunnelZone.getId().toString()).build(),
                    VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON,
                    DtoTunnelZone.class);
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

        /*
         * If there is a VTEP configured with a given tunnel zone, it should
         * not be possible to delete the tunnel zone.
         */
        @Test
        public void testDeleteFailsIfVTEPUsingTunnelZone() {

            DtoApplication app = topology.getApplication();
            URI tunnelZonesUri = app.getTunnelZones();
            DtoTunnelZone tunnelZone = new DtoTunnelZone();
            tunnelZone.setName("tz1");
            tunnelZone = dtoResource.postAndVerifyCreated(tunnelZonesUri,
                      VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON, tunnelZone,
                      DtoTunnelZone.class);
            DtoVtep vtep = new DtoVtep();
            vtep.setManagementIp(VtepDataClientProvider.MOCK_VTEP_MGMT_IP);
            vtep.setManagementPort(VtepDataClientProvider.MOCK_VTEP_MGMT_PORT);
            vtep.setTunnelZoneId(tunnelZone.getId());
            DtoVtep vtepDto = dtoResource.postAndVerifyCreated(
                app.getVteps(), VendorMediaType.APPLICATION_VTEP_JSON, vtep,
                DtoVtep.class);

            // now try to delete the tunnel zone
            dtoResource.deleteAndVerifyError(tunnelZone.getUri(),
                                 VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON,
                                 ClientResponse.Status.CONFLICT.getStatusCode());

        }

    }
}
