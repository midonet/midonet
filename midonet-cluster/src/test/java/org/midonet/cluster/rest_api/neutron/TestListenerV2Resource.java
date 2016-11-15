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
import org.midonet.cluster.rest_api.neutron.models.ListenerV2;
import org.midonet.cluster.rest_api.neutron.models.LoadBalancerV2;
import org.midonet.cluster.rest_api.neutron.resources.ListenerV2Resource;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@RunWith(MockitoJUnitRunner.class)
public class TestListenerV2Resource extends LoadBalancerV2TestBase {
    private ListenerV2Resource testListenerObject;

    @Before
    public void setUp() throws Exception {

        super.setUp();
        testListenerObject = new ListenerV2Resource(uriInfo, plugin);
    }

    @Test
    public void testListenerCreate() throws Exception {

        LoadBalancerV2 lb = lb();

        ListenerV2 input = listener(lb);
        ListenerV2 output = listener(lb, input.id);

        doReturn(output).when(plugin).createListenerV2(input);

        Response resp = testListenerObject.create(input);

        assertCreate(resp, output,
                NeutronUriBuilder.getListenerV2(BASE_URI, input.id));
    }

    @Test(expected = ConflictHttpException.class)
    public void testListenerCreateConflict() throws Exception {

        doThrow(ConflictHttpException.class).when(plugin).createListenerV2(
                any(ListenerV2.class));

        testListenerObject.create(new ListenerV2());
    }

    @Test(expected = NotFoundHttpException.class)
    public void testListenerGetNotFound() throws Exception {

        doReturn(null).when(plugin).getListenerV2(any(UUID.class));

        testListenerObject.get(UUID.randomUUID());
    }

    @Test
    public void testListenerUpdate() throws Exception {

        LoadBalancerV2 lb = lb();

        ListenerV2 input = listener(lb);
        ListenerV2 output = listener(lb, input.id);

        doReturn(output).when(plugin).updateListenerV2(input.id, input);

        Response resp = testListenerObject.update(input.id, input);

        assertUpdate(resp, output);
    }

    @Test(expected = NotFoundHttpException.class)
    public void testListenerUpdateNotFound() throws Exception {

        doThrow(NotFoundHttpException.class).when(plugin).updateListenerV2(
                any(UUID.class), any(ListenerV2.class));

        testListenerObject.update(any(UUID.class), any(ListenerV2.class));
    }
}
