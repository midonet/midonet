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
public class TestHealthMonitorV2Resource extends ResourceTest {

    private PoolV2Resource testPoolObject;
    private HealthMonitorV2Resource testHMObject;

    public static PoolV2 pool() {
        return pool(UUID.randomUUID());
    }

    public static PoolV2 pool(UUID id) {
        PoolV2 p = new PoolV2();
        p.id = id;
        return p;
    }

    public static HealthMonitorV2 hm() {
        return hm(UUID.randomUUID());
    }

    public static HealthMonitorV2 hm(UUID id) {
        HealthMonitorV2 hm= new HealthMonitorV2();
        hm.id = id;
        return hm;
    }

    @Before
    public void setUp() throws Exception {

        super.setUp();

        testPoolObject = new PoolV2Resource(uriInfo, plugin);
        testHMObject = new HealthMonitorV2Resource(uriInfo, plugin);
    }

    @Test
    public void testHMCreate() throws Exception {
        PoolV2 pool = pool();

        HealthMonitorV2 input = hm();
        HealthMonitorV2 output = hm(input.id);

        doReturn(output).when(plugin).createHealthMonitorV2(input);

        Response resp = testHMObject.create(input);

        assertCreate(resp, output,
                NeutronUriBuilder.getHealthMonitorV2(BASE_URI, input.id));
    }

    @Test(expected = ConflictHttpException.class)
    public void testHMCreateConflict() throws Exception {

        doThrow(ConflictHttpException.class).when(plugin).createHealthMonitorV2(
                any(HealthMonitorV2.class));

        testHMObject.create(new HealthMonitorV2());
    }

    @Test(expected = NotFoundHttpException.class)
    public void testHMGetNotFound() throws Exception {

        doReturn(null).when(plugin).getHealthMonitorV2(any(UUID.class));

        testHMObject.get(UUID.randomUUID());
    }

    @Test
    public void testHMUpdate() throws Exception {

        HealthMonitorV2 input = hm();
        HealthMonitorV2 output = hm(input.id);

        doReturn(output).when(plugin).updateHealthMonitorV2(input.id, input);

        Response resp = testHMObject.update(input.id, input);

        assertUpdate(resp, output);
    }

    @Test(expected = NotFoundHttpException.class)
    public void testHMUpdateNotFound() throws Exception {

        doThrow(NotFoundHttpException.class).when(plugin).updateHealthMonitorV2(
                any(UUID.class), any(HealthMonitorV2.class));

        testHMObject.update(any(UUID.class), any(HealthMonitorV2.class));
    }
}
