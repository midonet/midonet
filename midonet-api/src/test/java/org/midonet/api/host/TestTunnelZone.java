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

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;

import org.apache.zookeeper.KeeperException;
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
import org.midonet.api.vtep.VtepMockableDataClientFactory;
import org.midonet.client.MidonetApi;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoTunnelZone;
import org.midonet.client.dto.DtoVtep;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import static org.midonet.api.vtep.VtepMockableDataClientFactory.*;

@RunWith(Enclosed.class)
public class TestTunnelZone {

    public static class TestCrud extends RestApiTestBase {

        private DtoWebResource dtoResource;
        private HostTopology topology;
        private MidonetApi api;

        public TestCrud() {
            super(FuncTest.appDesc);
        }
        @Before
        public void setUp() throws StateAccessException,
                InterruptedException, KeeperException, SerializationException {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            topology = new HostTopology.Builder(dtoResource).build();

            URI baseUri = resource().getURI();
            api = new MidonetApi(baseUri.toString());
            api.enableLogging();

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
            vtep.setManagementIp(MOCK_VTEP_MGMT_IP);
            vtep.setManagementPort(MOCK_VTEP_MGMT_PORT);
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
