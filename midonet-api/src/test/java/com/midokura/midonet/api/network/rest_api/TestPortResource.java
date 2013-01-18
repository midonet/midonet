/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.network.rest_api;

import com.midokura.midonet.api.auth.ForbiddenHttpException;
import com.midokura.midonet.api.network.ExteriorRouterPort;
import com.midokura.midonet.api.network.RouterPort;
import com.midokura.midonet.api.rest_api.NotFoundHttpException;
import com.midokura.midonet.api.rest_api.ResourceFactory;
import com.midokura.midonet.api.auth.AuthAction;
import com.midokura.midonet.api.network.auth.PortAuthorizer;
import com.midokura.midonet.api.network.auth.RouterAuthorizer;
import com.midokura.midonet.api.rest_api.RestApiConfig;
import com.midokura.midonet.cluster.DataClient;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import javax.validation.Validator;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.UUID;

import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class TestPortResource {

    private PortResource testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private RestApiConfig config;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private SecurityContext context;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private ResourceFactory factory;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private PortAuthorizer auth;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private RouterAuthorizer routerAuth;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private Validator validator;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private UriInfo uriInfo;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private DataClient dataClient;

    @Before
    public void setUp() throws Exception {
        testObject = new PortResource(config, uriInfo, context, auth, validator,
                 dataClient, factory);
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testDeleteUnauthorized() throws Exception {
        // Set up
        UUID id = UUID.randomUUID();
        doReturn(false).when(auth).authorize(context, AuthAction.WRITE, id);

        // Execute
        testObject.delete(id);
    }

    @Test
    public void testDeleteNonExistentData() throws Exception {
        // Set up
        UUID id = UUID.randomUUID();
        doReturn(true).when(auth).authorize(context, AuthAction.WRITE, id);
        doReturn(null).when(dataClient).portsGet(id);

        // Execute
        testObject.delete(id);

        // Verify
        verify(dataClient, never()).portsDelete(id);
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testGetUnauthorized() throws Exception {
        // Set up
        UUID id = UUID.randomUUID();
        doReturn(false).when(auth).authorize(context, AuthAction.READ, id);

        // Execute
        testObject.get(id);
    }

    @Test(expected = NotFoundHttpException.class)
    public void testCreateBadRouterUUID() throws Exception {
        // Set up
        UUID id = UUID.randomUUID();
        doReturn(null).when(dataClient).routersGet(id);

        // Execute
        PortResource.RouterPortResource portResource = new PortResource.RouterPortResource(config, uriInfo, context, routerAuth, validator,
                dataClient, id);
        RouterPort routerPort = new ExteriorRouterPort(UUID.randomUUID(), id);
        portResource.create(routerPort);
    }

}
