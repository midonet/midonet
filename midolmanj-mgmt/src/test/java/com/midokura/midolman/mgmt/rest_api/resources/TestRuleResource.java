/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.RuleDao;
import com.midokura.midolman.mgmt.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.NoStatePathException;

@RunWith(MockitoJUnitRunner.class)
public class TestRuleResource {

    private RuleResource testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private SecurityContext context;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private DaoFactory factory;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private Authorizer auth;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private UriInfo uriInfo;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private RuleDao dao;

    @Before
    public void setUp() throws Exception {
        testObject = new RuleResource();
        doReturn(dao).when(factory).getRuleDao();
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testDeleteUnauthorized() throws Exception {
        // Set up
        UUID id = UUID.randomUUID();
        doReturn(false).when(auth).ruleAuthorized(context, AuthAction.WRITE,
                id);

        // Execute
        testObject.delete(id, context, factory, auth);
    }

    @Test
    public void testDeleteNonExistentData() throws Exception {
        // Set up
        UUID id = UUID.randomUUID();
        doReturn(true).when(auth).ruleAuthorized(context, AuthAction.WRITE,
                id);
        doThrow(NoStatePathException.class).when(dao).delete(id);

        // Execute
        testObject.delete(id, context, factory, auth);

        // Verify
        verify(dao, times(1)).delete(id);
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testGetUnauthorized() throws Exception {
        // Set up
        UUID id = UUID.randomUUID();
        doReturn(false).when(auth).ruleAuthorized(context, AuthAction.READ,
                id);

        // Execute
        testObject.get(id, context, uriInfo, factory, auth);
    }
}