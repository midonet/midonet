/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.rest_api;

import java.util.List;

import com.fasterxml.jackson.databind.JavaType;
import com.sun.jersey.api.client.WebResource;

import org.junit.Before;
import org.junit.Test;

import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoBgpPeer;
import org.midonet.client.dto.DtoRouter;
import org.midonet.cluster.rest_api.models.BgpPeer;
import org.midonet.cluster.rest_api.rest_api.DtoWebResource;
import org.midonet.cluster.rest_api.rest_api.RestApiTestBase;
import org.midonet.cluster.rest_api.rest_api.Topology;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertThat;
import static org.midonet.cluster.rest_api.rest_api.FuncTest.appDesc;
import static org.midonet.cluster.rest_api.rest_api.FuncTest.objectMapper;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_BGP_PEER_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_BGP_PEER_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_ROUTER_JSON_V3;

public class TestBgpPeer extends RestApiTestBase {

    private Topology topology;
    private DtoWebResource dtoResource;

    public TestBgpPeer() {
        super(appDesc);
    }

    @Before
    public void setUp() {
        WebResource resource = resource();
        dtoResource = new DtoWebResource(resource);

        Topology.Builder builder = new Topology.Builder(dtoResource);

        topology = builder.build();
    }

    @Test
    public void testCrud() throws Exception {
        DtoApplication app = topology.getApplication();

        // Given a router.
        DtoRouter router = new DtoRouter();
        router.setName("router-name");
        router.setTenantId("admin");
        DtoRouter routerResource = dtoResource.postAndVerifyCreated(
            app.getRouters(),
            APPLICATION_ROUTER_JSON_V3(), router,
            DtoRouter.class);

        // Create a BGP peer.
        DtoBgpPeer bgpPeer1 = new DtoBgpPeer();
        bgpPeer1.address = "1.2.3.4";
        bgpPeer1.asNumber = 1234;
        DtoBgpPeer bgpPeerResource1 = dtoResource.postAndVerifyCreated(
            routerResource.getBgpPeers(),
            APPLICATION_BGP_PEER_JSON(), bgpPeer1,
            DtoBgpPeer.class);

        // Listing the BGP peers.
        String response =
            dtoResource.getAndVerifyOk(routerResource.getBgpPeers(),
                                       APPLICATION_BGP_PEER_COLLECTION_JSON(),
                                       String.class);
        JavaType type = objectMapper.getTypeFactory()
            .constructParametrizedType(List.class, List.class, DtoBgpPeer.class);
        List<DtoBgpPeer> bgpPeers = objectMapper.readValue(response, type);

        assertThat(bgpPeers, hasSize(1));

        // Create another BGP peer.
        DtoBgpPeer bgpPeer2 = new DtoBgpPeer();
        bgpPeer2.address = "1.2.3.5";
        bgpPeer2.asNumber = 1234;
        DtoBgpPeer bgpPeerResource2 = dtoResource.postAndVerifyCreated(
            routerResource.getBgpPeers(),
            APPLICATION_BGP_PEER_JSON(), bgpPeer2,
            DtoBgpPeer.class);

        // Listing the BGP peers.
        response =
            dtoResource.getAndVerifyOk(routerResource.getBgpPeers(),
                                       APPLICATION_BGP_PEER_COLLECTION_JSON(),
                                       String.class);
        bgpPeers = objectMapper.readValue(response, type);

        assertThat(bgpPeers, hasSize(2));

        // Get a BGP peer.
        dtoResource.getAndVerifyOk(bgpPeerResource1.uri,
                                   APPLICATION_BGP_PEER_JSON(),
                                   BgpPeer.class);

        // Create a BGP peer without AS number should fail.
        DtoBgpPeer bgpPeer3 = new DtoBgpPeer();
        dtoResource.postAndVerifyBadRequest(routerResource.getBgpPeers(),
                                            APPLICATION_BGP_PEER_JSON(), bgpPeer3);

        // Create the same BGP peer should fail.
        dtoResource.postAndVerifyBadRequest(routerResource.getBgpPeers(),
                                            APPLICATION_BGP_PEER_JSON(), bgpPeer1);

        // Delete a BGP peer.
        dtoResource.deleteAndVerifyNoContent(bgpPeerResource1.uri,
                                             APPLICATION_BGP_PEER_JSON());

        // Listing the BGP peers.
        response =
            dtoResource.getAndVerifyOk(routerResource.getBgpPeers(),
                                       APPLICATION_BGP_PEER_COLLECTION_JSON(),
                                       String.class);
        bgpPeers = objectMapper.readValue(response, type);

        assertThat(bgpPeers, hasSize(1));
    }
}
