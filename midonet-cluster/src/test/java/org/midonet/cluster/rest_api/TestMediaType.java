/*
 * Copyright 2015 Midokura SARL
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import org.midonet.client.dto.DtoBridge;
import org.midonet.cluster.rest_api.rest_api.FuncTest;
import org.midonet.cluster.rest_api.rest_api.RestApiTestBase;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_BRIDGE_JSON_V4;

@RunWith(Parameterized.class)
public class TestMediaType extends RestApiTestBase {

    private final String mediaType;

    public TestMediaType(String mediaType) {
        super(FuncTest.appDesc);
        this.mediaType = mediaType;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> types() {
        List<Object[]> params = new ArrayList<>();
        params.add(new Object[] { APPLICATION_BRIDGE_JSON_V4() });
        params.add(new Object[] { APPLICATION_BRIDGE_JSON_V4() + "; charset=UTF-8"});
        params.add(new Object[] { APPLICATION_JSON });
        return params;
    }

    @Test
    public void testGet() {
        DtoBridge bridge = postBridge("bridge");
        dtoResource.getAndVerifyOk(bridge.getUri(), mediaType, DtoBridge.class);
    }

    @Test
    public void testPost() {
        DtoBridge bridge = new DtoBridge();
        bridge.setName("bridge");
        bridge.setTenantId("tenant1");
        dtoResource.postAndVerifyCreated(
            topology.getApplication().getBridges(), mediaType, bridge,
            DtoBridge.class);
    }
}
