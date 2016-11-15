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
public class TestLoadBalancerV2Resource extends ResourceTest {

    private LbV2Resource testLBObject;

    public static LoadBalancerV2 lb() {
        return lb(UUID.randomUUID());
    }

    public static LoadBalancerV2 lb(UUID id) {
        LoadBalancerV2 lb= new LoadBalancerV2();
        lb.id = id;
        return lb;
    }

    @Before
    public void setUp() throws Exception {

        super.setUp();

        testLBObject = new LbV2Resource(uriInfo, plugin);
    }

    @Test
    public void testLBCreate() throws Exception {

        LoadBalancerV2 input = lb();
        LoadBalancerV2 output = lb(input.id);

        doReturn(output).when(plugin).createLoadBalancerV2(input);

        Response resp = testLBObject.create(input);

        assertCreate(resp, output,
                NeutronUriBuilder.getLoadBalancerV2(BASE_URI, input.id));
    }

    @Test(expected = ConflictHttpException.class)
    public void testLBCreateConflict() throws Exception {

        doThrow(ConflictHttpException.class).when(plugin).createLoadBalancerV2(
                any(LoadBalancerV2.class));

        testLBObject.create(new LoadBalancerV2());
    }

    @Test(expected = NotFoundHttpException.class)
    public void testLBGetNotFound() throws Exception {

        doReturn(null).when(plugin).getLoadBalancerV2(any(UUID.class));

        testLBObject.get(UUID.randomUUID());
    }

    @Test
    public void testLBUpdate() throws Exception {

        LoadBalancerV2 input = lb();
        LoadBalancerV2 output = lb(input.id);

        doReturn(output).when(plugin).updateLoadBalancerV2(input.id, input);

        Response resp = testLBObject.update(input.id, input);

        assertUpdate(resp, output);
    }

    @Test(expected = NotFoundHttpException.class)
    public void testLBUpdateNotFound() throws Exception {

        doThrow(NotFoundHttpException.class).when(plugin).updateLoadBalancerV2(
                any(UUID.class), any(LoadBalancerV2.class));

        testLBObject.update(any(UUID.class), any(LoadBalancerV2.class));
    }
}
