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

package org.midonet.cluster.rest_api.neutron;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.midonet.cluster.rest_api.ConflictHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.ResourceTest;
import org.midonet.cluster.rest_api.neutron.models.*;
import org.midonet.cluster.rest_api.neutron.resources.*;
import org.mockito.runners.MockitoJUnitRunner;

import javax.ws.rs.core.Response;
import java.util.UUID;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@RunWith(MockitoJUnitRunner.class)
public class TestPoolV2Resource extends ResourceTest {

    private PoolV2Resource testPoolObject;

    public static PoolV2 pool() {
        return pool(UUID.randomUUID());
    }

    public static PoolV2 pool(UUID id) {
        PoolV2 p = new PoolV2();
        p.id = id;
        return p;
    }

    @Before
    public void setUp() throws Exception {

        super.setUp();

        testPoolObject = new PoolV2Resource(uriInfo, plugin);
    }

    @Test
    public void testPoolCreate() throws Exception {

        PoolV2 input = pool();
        PoolV2 output = pool(input.id);

        doReturn(output).when(plugin).createPoolV2(input);

        Response resp = testPoolObject.create(input);

        assertCreate(resp, output,
                NeutronUriBuilder.getPoolV2(BASE_URI, input.id));
    }

    @Test(expected = ConflictHttpException.class)
    public void testPoolCreateConflict() throws Exception {

        doThrow(ConflictHttpException.class).when(plugin).createPoolV2(
                any(PoolV2.class));

        testPoolObject.create(new PoolV2());
    }

    @Test(expected = NotFoundHttpException.class)
    public void testPoolGetNotFound() throws Exception {

        doReturn(null).when(plugin).getPoolV2(any(UUID.class));

        testPoolObject.get(UUID.randomUUID());
    }

    @Test
    public void testPoolUpdate() throws Exception {

        PoolV2 input = pool();
        PoolV2 output = pool(input.id);

        doReturn(output).when(plugin).updatePoolV2(input.id, input);

        Response resp = testPoolObject.update(input.id, input);

        assertUpdate(resp, output);
    }

    @Test(expected = NotFoundHttpException.class)
    public void testPoolUpdateNotFound() throws Exception {

        doThrow(NotFoundHttpException.class).when(plugin).updatePoolV2(
                any(UUID.class), any(PoolV2.class));

        testPoolObject.update(any(UUID.class), any(PoolV2.class));
    }
}
