/*
 * @(#)TestBgpResource        1.6 12/01/16
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
import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.BgpDao;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

public class TestBgpResource {

    private SecurityContext contextMock = null;
    private DaoFactory factoryMock = null;
    private Authorizer authMock = null;
    private UriInfo uriInfoMock = null;
    private BgpResource resource = null;
    private BgpDao daoMock = null;

    @Before
    public void setUp() throws Exception {
        this.contextMock = mock(SecurityContext.class);
        this.factoryMock = mock(DaoFactory.class);
        this.authMock = mock(Authorizer.class);
        this.daoMock = mock(BgpDao.class);
        this.uriInfoMock = mock(UriInfo.class);
        this.resource = new BgpResource();

        doReturn(daoMock).when(factoryMock).getBgpDao();
    }

    @Test
    public void testDeleteSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(true).when(authMock).bgpAuthorized(contextMock,
                AuthAction.WRITE, id);
        resource.delete(id, contextMock, factoryMock, authMock);

        verify(daoMock, times(1)).delete(id);
    }

    @Test(expected = UnauthorizedException.class)
    public void testDeleteUnauthorized() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(false).when(authMock).bgpAuthorized(contextMock,
                AuthAction.WRITE, id);
        resource.delete(id, contextMock, factoryMock, authMock);
    }

    @Test
    public void testDeleteNonExistentData() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(true).when(authMock).bgpAuthorized(contextMock,
                AuthAction.WRITE, id);
        doThrow(NoStatePathException.class).when(daoMock).delete(id);

        resource.delete(id, contextMock, factoryMock, authMock);

        verify(daoMock, times(1)).delete(id);
    }

    @Test(expected = StateAccessException.class)
    public void testDeleteDataAccessError() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(true).when(authMock).bgpAuthorized(contextMock,
                AuthAction.WRITE, id);
        doThrow(StateAccessException.class).when(daoMock).delete(id);
        resource.delete(id, contextMock, factoryMock, authMock);
    }

    @Test(expected = UnknownRestApiException.class)
    public void testDeleteUnknownError() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(true).when(authMock).bgpAuthorized(contextMock,
                AuthAction.WRITE, id);
        doThrow(RuntimeException.class).when(daoMock).delete(id);
        resource.delete(id, contextMock, factoryMock, authMock);
    }

    private Bgp getBgp(URI baseUri) {
        Bgp bgp = new Bgp();
        bgp.setBaseUri(baseUri);
        return bgp;
    }

    @Test
    public void testGetSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        URI uri = URI.create("http://www.foo.com");
        Bgp bgp = getBgp(uri);
        doReturn(true).when(authMock).bgpAuthorized(contextMock,
                AuthAction.READ, id);
        doReturn(uri).when(uriInfoMock).getBaseUri();
        doReturn(bgp).when(daoMock).get(id);

        Bgp result = resource.get(id, contextMock, uriInfoMock,
                factoryMock, authMock);

        Assert.assertEquals(bgp, result);
        Assert.assertEquals(uri, result.getBaseUri());
    }

    @Test(expected = UnauthorizedException.class)
    public void testGetUnauthorized() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(false).when(authMock).bgpAuthorized(contextMock,
                AuthAction.READ, id);
        resource.get(id, contextMock, uriInfoMock, factoryMock, authMock);
    }

    @Test(expected = StateAccessException.class)
    public void testGetDataAccessError() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(true).when(authMock).bgpAuthorized(contextMock,
                AuthAction.READ, id);
        doThrow(StateAccessException.class).when(daoMock).get(id);
        resource.get(id, contextMock, uriInfoMock, factoryMock, authMock);
    }

    @Test(expected = UnknownRestApiException.class)
    public void testGetUnknownError() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(true).when(authMock).bgpAuthorized(contextMock,
                AuthAction.READ, id);
        doThrow(RuntimeException.class).when(daoMock).get(id);
        resource.get(id, contextMock, uriInfoMock, factoryMock, authMock);
    }
}
