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
public class TestPoolMemberV2Resource extends ResourceTest {

    private PoolV2Resource testPoolObject;
    private PoolMemberV2Resource testMemberObject;

    public static PoolV2 pool() {
        return pool(UUID.randomUUID());
    }

    public static PoolV2 pool(UUID id) {
        PoolV2 p = new PoolV2();
        p.id = id;
        return p;
    }

    public static PoolMemberV2 member(PoolV2 pool) {
        return member(pool, UUID.randomUUID());
    }

    public static PoolMemberV2 member(PoolV2 pool, UUID id) {
        PoolMemberV2 m = new PoolMemberV2();
        m.poolId = pool.id;
        m.id = id;
        pool.addMember(m.id);
        return m;
    }

    @Before
    public void setUp() throws Exception {

        super.setUp();

        testPoolObject = new PoolV2Resource(uriInfo, plugin);
        testMemberObject = new PoolMemberV2Resource(uriInfo, plugin);
    }

    @Test
    public void testMemberCreate() throws Exception {
        PoolV2 pool = pool();

        PoolMemberV2 input = member(pool);
        PoolMemberV2 output = member(pool, input.id);

        doReturn(output).when(plugin).createPoolMemberV2(input);

        Response resp = testMemberObject.create(input);

        assertCreate(resp, output,
                NeutronUriBuilder.getPoolMemberV2(BASE_URI, input.id));
    }

    @Test(expected = ConflictHttpException.class)
    public void testMemberCreateConflict() throws Exception {

        doThrow(ConflictHttpException.class).when(plugin).createPoolMemberV2(
                any(PoolMemberV2.class));

        testMemberObject.create(new PoolMemberV2());
    }

    @Test(expected = NotFoundHttpException.class)
    public void testMemberGetNotFound() throws Exception {

        doReturn(null).when(plugin).getPoolMemberV2(any(UUID.class));

        testMemberObject.get(UUID.randomUUID());
    }

    @Test
    public void testMemberUpdate() throws Exception {
        PoolV2 pool = pool();

        PoolMemberV2 input = member(pool);
        PoolMemberV2 output = member(pool, input.id);

        doReturn(output).when(plugin).updatePoolMemberV2(input.id, input);

        Response resp = testMemberObject.update(input.id, input);

        assertUpdate(resp, output);
    }

    @Test(expected = NotFoundHttpException.class)
    public void testMemberUpdateNotFound() throws Exception {

        doThrow(NotFoundHttpException.class).when(plugin).updatePoolMemberV2(
                any(UUID.class), any(PoolMemberV2.class));

        testMemberObject.update(any(UUID.class), any(PoolMemberV2.class));
    }
}
