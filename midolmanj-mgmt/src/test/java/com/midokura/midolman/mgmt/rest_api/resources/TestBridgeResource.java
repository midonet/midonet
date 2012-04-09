/*
 * @(#)TestBridgeResource        1.6 12/01/18
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

import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

public class TestBridgeResource {

    private SecurityContext contextMock = null;
    private DaoFactory factoryMock = null;
    private Authorizer authMock = null;
    private UriInfo uriInfoMock = null;
    private BridgeResource resource = null;
    private BridgeDao daoMock = null;

    @Before
    public void setUp() throws Exception {
        this.contextMock = mock(SecurityContext.class);
        this.factoryMock = mock(DaoFactory.class);
        this.authMock = mock(Authorizer.class);
        this.daoMock = mock(BridgeDao.class);
        this.uriInfoMock = mock(UriInfo.class);
        this.resource = new BridgeResource();

        doReturn(daoMock).when(factoryMock).getBridgeDao();
    }

    @Test
    public void testDeleteSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(true).when(authMock).bridgeAuthorized(contextMock,
                AuthAction.WRITE, id);
        resource.delete(id, contextMock, factoryMock, authMock);

        verify(daoMock, times(1)).delete(id);
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testDeleteUnauthorized() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(false).when(authMock).bridgeAuthorized(contextMock,
                AuthAction.WRITE, id);
        resource.delete(id, contextMock, factoryMock, authMock);
    }

    @Test
    public void testDeleteNonExistentData() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(true).when(authMock).bridgeAuthorized(contextMock,
                AuthAction.WRITE, id);
        doThrow(NoStatePathException.class).when(daoMock).delete(id);

        resource.delete(id, contextMock, factoryMock, authMock);

        verify(daoMock, times(1)).delete(id);
    }

    @Test(expected = StateAccessException.class)
    public void testDeleteDataAccessError() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(true).when(authMock).bridgeAuthorized(contextMock,
                AuthAction.WRITE, id);
        doThrow(StateAccessException.class).when(daoMock).delete(id);
        resource.delete(id, contextMock, factoryMock, authMock);
    }

    private Bridge getBridge(URI baseUri) {
        Bridge bridge = new Bridge();
        bridge.setBaseUri(baseUri);
        return bridge;
    }

    @Test
    public void testGetSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        URI uri = URI.create("http://www.foo.com");
        Bridge bridge = getBridge(uri);
        doReturn(true).when(authMock).bridgeAuthorized(contextMock,
                AuthAction.READ, id);
        doReturn(uri).when(uriInfoMock).getBaseUri();
        doReturn(bridge).when(daoMock).get(id);

        Bridge result = resource.get(id, contextMock, uriInfoMock, factoryMock,
                authMock);

        Assert.assertEquals(bridge, result);
        Assert.assertEquals(uri, result.getBaseUri());
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testGetUnauthorized() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(false).when(authMock).bridgeAuthorized(contextMock,
                AuthAction.READ, id);
        resource.get(id, contextMock, uriInfoMock, factoryMock, authMock);
    }

    @Test(expected = StateAccessException.class)
    public void testGetDataAccessError() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(true).when(authMock).bridgeAuthorized(contextMock,
                AuthAction.READ, id);
        doThrow(StateAccessException.class).when(daoMock).get(id);
        resource.get(id, contextMock, uriInfoMock, factoryMock, authMock);
    }

    @Test
    public void testUpdateSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        URI uri = URI.create("http://www.foo.com");
        Bridge bridge = getBridge(uri);
        doReturn(true).when(authMock).bridgeAuthorized(contextMock,
                AuthAction.WRITE, id);
        doReturn(uri).when(uriInfoMock).getBaseUri();

        Response resp = resource.update(id, bridge, contextMock, factoryMock,
                authMock);

        Assert.assertEquals(id, bridge.getId());
        Assert.assertEquals(Response.Status.OK.getStatusCode(),
                resp.getStatus());
        verify(daoMock, times(1)).update(bridge);
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testUpdateUnauthorized() throws Exception {
        UUID id = UUID.randomUUID();
        URI uri = URI.create("http://www.foo.com");
        Bridge bridge = getBridge(uri);
        doReturn(false).when(authMock).bridgeAuthorized(contextMock,
                AuthAction.WRITE, id);
        resource.update(id, bridge, contextMock, factoryMock, authMock);
    }

    @Test(expected = StateAccessException.class)
    public void testUpdateDataAccessError() throws Exception {
        UUID id = UUID.randomUUID();
        URI uri = URI.create("http://www.foo.com");
        Bridge bridge = getBridge(uri);
        doReturn(true).when(authMock).bridgeAuthorized(contextMock,
                AuthAction.WRITE, id);
        doThrow(StateAccessException.class).when(daoMock).update(bridge);
        resource.update(id, bridge, contextMock, factoryMock, authMock);
    }
}
