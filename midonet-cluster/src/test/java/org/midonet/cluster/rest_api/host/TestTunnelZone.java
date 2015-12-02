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

import org.midonet.client.MidonetApi;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoTunnelZone;
import org.midonet.client.dto.DtoVtep;
import org.midonet.client.dto.TunnelZoneType;
import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.cluster.rest_api.host.rest_api.HostTopology;
import org.midonet.cluster.rest_api.rest_api.DtoWebResource;
import org.midonet.cluster.rest_api.rest_api.FuncTest;
import org.midonet.cluster.rest_api.rest_api.RestApiTestBase;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;
import org.midonet.midolman.serialization.SerializationException;

import static javax.ws.rs.core.Response.Status.*;
import static org.midonet.cluster.rest_api.validation.MessageProperty.UNIQUE_TUNNEL_ZONE_NAME_TYPE;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TUNNEL_ZONE_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_TUNNEL_ZONE_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_VTEP_JSON_V2;

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
                    APPLICATION_TUNNEL_ZONE_JSON(), tunnelZone,
                    DtoTunnelZone.class);
            Assert.assertNotNull(tunnelZone.getId());
            Assert.assertEquals("tz1-name", tunnelZone.getName());

            // Do not allow duplicates
            DtoTunnelZone tunnelZone2 = new DtoTunnelZone();
            tunnelZone2.setName("tz1-name");
            DtoError error = dtoResource.postAndVerifyError(tunnelZonesUri,
                APPLICATION_TUNNEL_ZONE_JSON(), tunnelZone2, CONFLICT);
            assertErrorMatches(error, UNIQUE_TUNNEL_ZONE_NAME_TYPE);

            // There should only be one
            DtoTunnelZone[] tunnelZones = dtoResource.getAndVerifyOk(
                tunnelZonesUri,
                APPLICATION_TUNNEL_ZONE_COLLECTION_JSON(),
                DtoTunnelZone[].class);
            Assert.assertEquals(1, tunnelZones.length);

            // Update tunnel zone name
            tunnelZone.setName("tz1-name-updated");
            tunnelZone = dtoResource.putAndVerifyNoContent(tunnelZone.getUri(),
                    APPLICATION_TUNNEL_ZONE_JSON(), tunnelZone,
                    DtoTunnelZone.class);
            Assert.assertEquals("tz1-name-updated", tunnelZone.getName());

            // List and make sure that there is one
            tunnelZones = dtoResource.getAndVerifyOk(
                    tunnelZonesUri,
                    APPLICATION_TUNNEL_ZONE_COLLECTION_JSON(),
                    DtoTunnelZone[].class);
            Assert.assertEquals(1, tunnelZones.length);

            // Get the tunnel zone building the URI by hand.
            DtoTunnelZone tZone = dtoResource.getAndVerifyOk(
                    UriBuilder.fromUri(tunnelZonesUri)
                        .path(tunnelZone.getId().toString()).build(),
                    APPLICATION_TUNNEL_ZONE_JSON(),
                    DtoTunnelZone.class);
            Assert.assertEquals(tunnelZone.getType(), tZone.getType());
            Assert.assertEquals(tunnelZone.getName(), tZone.getName());

            // Getting a non-existent zone returns a 404.
            dtoResource.getAndVerifyNotFound(
                UriBuilder.fromUri(tunnelZonesUri)
                    .path(UUID.randomUUID().toString()).build(),
                APPLICATION_TUNNEL_ZONE_JSON());

            // Delete it
            dtoResource.deleteAndVerifyNoContent(tunnelZone.getUri(),
                    APPLICATION_TUNNEL_ZONE_JSON());

            // list and make sure it's gone
            tunnelZones = dtoResource.getAndVerifyOk(
                    tunnelZonesUri,
                    APPLICATION_TUNNEL_ZONE_COLLECTION_JSON(),
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
            tunnelZone.setType(TunnelZoneType.VTEP);
            tunnelZone = dtoResource.postAndVerifyCreated(tunnelZonesUri,
                      MidonetMediaTypes.APPLICATION_TUNNEL_ZONE_JSON(), tunnelZone,
                      DtoTunnelZone.class);
            DtoVtep vtep = new DtoVtep();
            vtep.setManagementIp("192.168.1.2");
            vtep.setManagementPort(6632);
            vtep.setTunnelZoneId(tunnelZone.getId());
            dtoResource.postAndVerifyCreated(app.getVteps(),
                                             APPLICATION_VTEP_JSON_V2(), vtep,
                                             DtoVtep.class);

            // now try to delete the tunnel zone
            dtoResource.deleteAndVerifyError(tunnelZone.getUri(),
                                 MidonetMediaTypes.APPLICATION_TUNNEL_ZONE_JSON(),
                                 ClientResponse.Status.CONFLICT.getStatusCode());
        }

    }
}
