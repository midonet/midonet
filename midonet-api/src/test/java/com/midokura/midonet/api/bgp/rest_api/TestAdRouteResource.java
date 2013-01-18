/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.bgp.rest_api;

import com.midokura.midonet.api.auth.ForbiddenHttpException;
import com.midokura.midonet.api.bgp.auth.AdRouteAuthorizer;
import com.midokura.midonet.api.rest_api.RestApiConfig;
import com.midokura.midonet.api.auth.AuthAction;
import com.midokura.midonet.cluster.DataClient;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.util.UUID;

import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class TestAdRouteResource {

    private AdRouteResource testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private RestApiConfig config;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private SecurityContext context;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private AdRouteAuthorizer auth;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private UriInfo uriInfo;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private DataClient dataClient;

    @Before
    public void setUp() throws Exception {
        testObject = new AdRouteResource(config, uriInfo, context, auth,
                dataClient);
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
        doReturn(null).when(dataClient).adRoutesGet(id);

        // Execute
        testObject.delete(id);

        // Verify
        verify(dataClient, never()).adRoutesDelete(id);
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testGetUnauthorized() throws Exception {
        // Set up
        UUID id = UUID.randomUUID();
        doReturn(false).when(auth).authorize(context, AuthAction.READ, id);

        // Execute
        testObject.get(id);
    }

}
