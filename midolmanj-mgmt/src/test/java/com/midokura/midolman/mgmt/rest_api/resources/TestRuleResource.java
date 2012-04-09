/*
 * @(#)TestRuleResource        1.6 12/01/16
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.RuleDao;
import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

public class TestRuleResource {

    private SecurityContext contextMock = null;
    private DaoFactory factoryMock = null;
    private Authorizer authMock = null;
    private UriInfo uriInfoMock = null;
    private RuleResource resource = null;
    private RuleDao daoMock = null;

    @Before
    public void setUp() throws Exception {
        this.contextMock = mock(SecurityContext.class);
        this.factoryMock = mock(DaoFactory.class);
        this.authMock = mock(Authorizer.class);
        this.daoMock = mock(RuleDao.class);
        this.uriInfoMock = mock(UriInfo.class);
        this.resource = new RuleResource();

        doReturn(daoMock).when(factoryMock).getRuleDao();
    }

    @Test
    public void testDeleteSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(true).when(authMock).ruleAuthorized(contextMock,
                AuthAction.WRITE, id);
        resource.delete(id, contextMock, factoryMock, authMock);

        verify(daoMock, times(1)).delete(id);
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testDeleteUnauthorized() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(false).when(authMock).ruleAuthorized(contextMock,
                AuthAction.WRITE, id);
        resource.delete(id, contextMock, factoryMock, authMock);
    }

    @Test
    public void testDeleteNonExistentData() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(true).when(authMock).ruleAuthorized(contextMock,
                AuthAction.WRITE, id);
        doThrow(NoStatePathException.class).when(daoMock).delete(id);

        resource.delete(id, contextMock, factoryMock, authMock);

        verify(daoMock, times(1)).delete(id);
    }

    @Test(expected = StateAccessException.class)
    public void testDeleteDataAccessError() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(true).when(authMock).ruleAuthorized(contextMock,
                AuthAction.WRITE, id);
        doThrow(StateAccessException.class).when(daoMock).delete(id);
        resource.delete(id, contextMock, factoryMock, authMock);
    }

    private Rule getRule(URI baseUri) {
        Rule rule = new Rule();
        rule.setBaseUri(baseUri);
        return rule;
    }

    @Test
    public void testGetSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        URI uri = URI.create("http://www.foo.com");
        Rule rule = getRule(uri);
        doReturn(true).when(authMock).ruleAuthorized(contextMock,
                AuthAction.READ, id);
        doReturn(uri).when(uriInfoMock).getBaseUri();
        doReturn(rule).when(daoMock).get(id);

        Rule result = resource.get(id, contextMock, uriInfoMock,
                factoryMock, authMock);

        Assert.assertEquals(rule, result);
        Assert.assertEquals(uri, result.getBaseUri());
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testGetUnauthorized() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(false).when(authMock).ruleAuthorized(contextMock,
                AuthAction.READ, id);
        resource.get(id, contextMock, uriInfoMock, factoryMock, authMock);
    }

    @Test(expected = StateAccessException.class)
    public void testGetDataAccessError() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(true).when(authMock).ruleAuthorized(contextMock,
                AuthAction.READ, id);
        doThrow(StateAccessException.class).when(daoMock).get(id);
        resource.get(id, contextMock, uriInfoMock, factoryMock, authMock);
    }
}
