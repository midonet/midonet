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

import java.util.UUID;

import javax.ws.rs.core.Response;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import org.midonet.cluster.rest_api.ConflictHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.neutron.models.HealthMonitorV2;
import org.midonet.cluster.rest_api.neutron.resources.HealthMonitorV2Resource;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@RunWith(MockitoJUnitRunner.class)
public class TestHealthMonitorV2Resource extends LoadBalancerV2TestBase {

    private HealthMonitorV2Resource testHMObject;

    @Before
    public void setUp() throws Exception {

        super.setUp();

        testHMObject = new HealthMonitorV2Resource(uriInfo, plugin);
    }

    @Test
    public void testHMCreate() throws Exception {
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
