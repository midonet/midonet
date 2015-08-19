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
package org.midonet.api.neutron;

import java.util.UUID;

import javax.ws.rs.core.Response;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import org.midonet.api.ResourceTest;
import org.midonet.cluster.rest_api.neutron.NeutronUriBuilder;
import org.midonet.cluster.rest_api.neutron.models.Router;
import org.midonet.cluster.rest_api.neutron.models.RouterInterface;
import org.midonet.cluster.rest_api.ConflictHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.cluster.rest_api.neutron.resources.RouterResource;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

@RunWith(MockitoJUnitRunner.class)
public class TestRouterResource extends ResourceTest {

    private RouterResource testObject;

    public static Router router() {
        return router(UUID.randomUUID());
    }

    public static Router router(UUID id) {
        Router r= new Router();
        r.id = id;
        return r;
    }

    public static RouterInterface routerInterface() {
        return routerInterface(UUID.randomUUID());
    }

    public static RouterInterface routerInterface(UUID id) {
        RouterInterface ri = new RouterInterface();
        ri.id = id;
        return ri;
    }

    @Before
    public void setUp() throws Exception {

        super.setUp();

        testObject = new RouterResource(uriInfo, plugin);
    }

    @Test
    public void testCreate() throws Exception {

        Router input = router();
        Router output = router(input.id);

        doReturn(output).when(plugin).createRouter(input);

        Response resp = testObject.create(input);

        assertCreate(resp, output,
                NeutronUriBuilder.getRouter(BASE_URI, input.id));
    }

    @Test(expected = ConflictHttpException.class)
    public void testCreateConflict() throws Exception {

        doThrow(ConflictHttpException.class).when(plugin).createRouter(
                any(Router.class));

        testObject.create(new Router());
    }

    @Test(expected = NotFoundHttpException.class)
    public void testGetNotFound() throws Exception {

        doReturn(null).when(plugin).getRouter(any(UUID.class));

        testObject.get(UUID.randomUUID());
    }

    @Test
    public void testUpdate() throws Exception {

        Router input = router();
        Router output = router(input.id);

        doReturn(output).when(plugin).updateRouter(input.id, input);

        Response resp = testObject.update(input.id, input);

        assertUpdate(resp, output);
    }

    @Test(expected = NotFoundHttpException.class)
    public void testUpdateNotFound() throws Exception {

        doThrow(NotFoundHttpException.class).when(plugin).updateRouter(
                any(UUID.class), any(Router.class));

        testObject.update(any(UUID.class), any(Router.class));
    }

    @Test(expected = NotFoundHttpException.class)
    public void testAddRouterInterfaceNotFound() throws Exception {

        doThrow(NotFoundHttpException.class).when(
            plugin).addRouterInterface(
                any(UUID.class), any(RouterInterface.class));

        UUID id = UUID.randomUUID();
        RouterInterface ri = routerInterface(id);
        testObject.addRouterInterface(id, ri);
    }
}
