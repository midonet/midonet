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
package org.midonet.api.network.rest_api;

import java.util.UUID;

import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import org.midonet.api.auth.ForbiddenHttpException;
import org.midonet.api.rest_api.Authoriser;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Route;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class TestRouteResource {

    private RouteResource testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private RestApiConfig config;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private SecurityContext context;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private Authoriser auth;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private UriInfo uriInfo;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private DataClient dataClient;

    @Before
    public void setUp() throws Exception {
        testObject = new RouteResource(config, uriInfo, context, dataClient,
                                       auth);
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testDeleteUnauthorized() throws Exception {
        // Set up
        UUID id = UUID.randomUUID();
        Route r = new Route();
        r.setRouterId(UUID.randomUUID());

        doReturn(r).when(dataClient).routesGet(id);
        doThrow(ForbiddenHttpException.class)
            .when(auth).tryAuthoriseRouter(r.getRouterId(),
                                           "delete this route");

        // Execute
        testObject.delete(id);
    }

    @Test
    public void testDeleteNonExistentData() throws Exception {
        // Set up
        UUID id = UUID.randomUUID();
        doReturn(null).when(dataClient).routesGet(id);

        // Execute
        testObject.delete(id);

        // Verify
        verify(dataClient, never()).routesDelete(id);
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testGetUnauthorized() throws Exception {
        // Set up
        UUID id = UUID.randomUUID();
        Route r = new Route();
        r.setRouterId(UUID.randomUUID());

        doReturn(r).when(dataClient).routesGet(id);
        doThrow(ForbiddenHttpException.class)
            .when(auth).tryAuthoriseRouter(r.getRouterId(), "view this route");

        // Execute
        testObject.get(id);
    }
}
