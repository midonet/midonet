/*
 * @(#)TestVifResource        1.6 12/01/16
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.resources;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.mgmt.auth.AuthAction;
import com.midokura.midolman.mgmt.auth.Authorizer;
import com.midokura.midolman.mgmt.auth.UnauthorizedException;
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.VifDao;
import com.midokura.midolman.mgmt.data.dto.Vif;
import com.midokura.midolman.mgmt.rest_api.jaxrs.UnknownRestApiException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

public class TestVifResource {

    private SecurityContext contextMock = null;
    private DaoFactory factoryMock = null;
    private Authorizer authMock = null;
    private UriInfo uriInfoMock = null;
    private VifResource resource = null;
    private VifDao daoMock = null;

    @Before
    public void setUp() throws Exception {
        this.contextMock = mock(SecurityContext.class);
        this.factoryMock = mock(DaoFactory.class);
        this.authMock = mock(Authorizer.class);
        this.daoMock = mock(VifDao.class);
        this.uriInfoMock = mock(UriInfo.class);
        this.resource = new VifResource();

        doReturn(daoMock).when(factoryMock).getVifDao();
    }

    private Vif getVif(URI baseUri, UUID portId) {
        Vif vif = new Vif();
        if (baseUri != null) {
            vif.setBaseUri(baseUri);
        }
        if (portId != null) {
            vif.setPortId(portId);
        }
        return vif;
    }

    private List<Vif> getVifs() {
        List<Vif> vifs = new ArrayList<Vif>();
        vifs.add(new Vif(UUID.randomUUID(), UUID.randomUUID()));
        vifs.add(new Vif(UUID.randomUUID(), UUID.randomUUID()));
        vifs.add(new Vif(UUID.randomUUID(), UUID.randomUUID()));
        return vifs;
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateNoPort() throws Exception {
        Vif vif = getVif(null, null);
        resource.create(vif, uriInfoMock, contextMock, factoryMock, authMock);
    }

    @Test(expected = UnauthorizedException.class)
    public void testCreateUnauthorized() throws Exception {
        Vif vif = getVif(null, UUID.randomUUID());
        doReturn(false).when(authMock).vifAuthorized(contextMock,
                AuthAction.WRITE, vif.getPortId());
        resource.create(vif, uriInfoMock, contextMock, factoryMock, authMock);
    }

    @Test(expected = StateAccessException.class)
    public void testCreateDataAccessError() throws Exception {
        Vif vif = getVif(null, UUID.randomUUID());
        doReturn(true).when(authMock).vifAuthorized(contextMock,
                AuthAction.WRITE, vif.getPortId());
        doThrow(StateAccessException.class).when(daoMock).create(vif);
        resource.create(vif, uriInfoMock, contextMock, factoryMock, authMock);
    }

    @Test(expected = UnknownRestApiException.class)
    public void testCreateUnknownError() throws Exception {
        Vif vif = getVif(null, UUID.randomUUID());
        doReturn(true).when(authMock).vifAuthorized(contextMock,
                AuthAction.WRITE, vif.getPortId());
        doThrow(RuntimeException.class).when(daoMock).create(vif);
        resource.create(vif, uriInfoMock, contextMock, factoryMock, authMock);
    }

    @Test
    public void testCreateSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        URI uri = URI.create("http://foo.com");
        Vif vif = getVif(null, UUID.randomUUID());
        doReturn(true).when(authMock).vifAuthorized(contextMock,
                AuthAction.WRITE, vif.getPortId());
        doReturn(id).when(daoMock).create(vif);
        doReturn(uri).when(uriInfoMock).getBaseUri();
        Response resp = resource.create(vif, uriInfoMock, contextMock,
                factoryMock, authMock);

        Assert.assertEquals(Response.Status.CREATED.getStatusCode(),
                resp.getStatus());
    }

    @Test
    public void testDeleteSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        Vif vif = getVif(null, UUID.randomUUID());
        doReturn(vif).when(daoMock).get(id);
        doReturn(true).when(authMock).vifAuthorized(contextMock,
                AuthAction.WRITE, vif.getPortId());
        resource.delete(id, contextMock, factoryMock, authMock);

        verify(daoMock, times(1)).delete(id);
    }

    @Test(expected = UnauthorizedException.class)
    public void testDeleteUnauthorized() throws Exception {
        UUID id = UUID.randomUUID();
        Vif vif = getVif(null, UUID.randomUUID());
        doReturn(vif).when(daoMock).get(id);
        doReturn(false).when(authMock).vifAuthorized(contextMock,
                AuthAction.WRITE, vif.getPortId());
        resource.delete(id, contextMock, factoryMock, authMock);
    }

    @Test
    public void testDeleteNonExistentData() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(NoStatePathException.class).when(daoMock).get(id);
        resource.delete(id, contextMock, factoryMock, authMock);
        verify(daoMock, never()).delete(id);
    }

    @Test(expected = StateAccessException.class)
    public void testDeleteDataAccessError() throws Exception {
        UUID id = UUID.randomUUID();
        Vif vif = getVif(null, UUID.randomUUID());
        doReturn(vif).when(daoMock).get(id);
        doReturn(true).when(authMock).vifAuthorized(contextMock,
                AuthAction.WRITE, vif.getPortId());
        doThrow(StateAccessException.class).when(daoMock).delete(id);
        resource.delete(id, contextMock, factoryMock, authMock);
    }

    @Test(expected = UnknownRestApiException.class)
    public void testDeleteUnknownError() throws Exception {
        UUID id = UUID.randomUUID();
        Vif vif = getVif(null, UUID.randomUUID());
        doReturn(vif).when(daoMock).get(id);
        doReturn(true).when(authMock).vifAuthorized(contextMock,
                AuthAction.WRITE, vif.getPortId());
        doThrow(RuntimeException.class).when(daoMock).delete(id);
        resource.delete(id, contextMock, factoryMock, authMock);
    }

    @Test
    public void testGetSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        URI uri = URI.create("http://www.foo.com");
        Vif vif = getVif(null, UUID.randomUUID());
        doReturn(vif).when(daoMock).get(id);
        doReturn(true).when(authMock).vifAuthorized(contextMock,
                AuthAction.READ, vif.getPortId());
        doReturn(uri).when(uriInfoMock).getBaseUri();

        Vif result = resource.get(id, contextMock, uriInfoMock, factoryMock,
                authMock);

        Assert.assertEquals(vif, result);
        Assert.assertEquals(uri, result.getBaseUri());
    }

    @Test(expected = UnauthorizedException.class)
    public void testGetUnauthorized() throws Exception {
        UUID id = UUID.randomUUID();
        Vif vif = getVif(null, UUID.randomUUID());
        doReturn(vif).when(daoMock).get(id);
        doReturn(false).when(authMock).vifAuthorized(contextMock,
                AuthAction.READ, vif.getPortId());
        resource.get(id, contextMock, uriInfoMock, factoryMock, authMock);
    }

    @Test(expected = StateAccessException.class)
    public void testGetDataAccessError() throws Exception {
        UUID id = UUID.randomUUID();
        Vif vif = getVif(null, UUID.randomUUID());
        doReturn(vif).when(daoMock).get(id);
        doReturn(true).when(authMock).vifAuthorized(contextMock,
                AuthAction.READ, vif.getPortId());
        doThrow(StateAccessException.class).when(daoMock).get(id);
        resource.get(id, contextMock, uriInfoMock, factoryMock, authMock);
    }

    @Test(expected = UnknownRestApiException.class)
    public void testGetUnknownError() throws Exception {
        UUID id = UUID.randomUUID();
        Vif vif = getVif(null, UUID.randomUUID());
        doReturn(vif).when(daoMock).get(id);
        doReturn(true).when(authMock).vifAuthorized(contextMock,
                AuthAction.READ, vif.getPortId());
        doThrow(RuntimeException.class).when(daoMock).get(id);
        resource.get(id, contextMock, uriInfoMock, factoryMock, authMock);
    }

    @Test(expected = UnauthorizedException.class)
    public void testListUnauthorized() throws Exception {
        doReturn(false).when(authMock).isAdmin(contextMock);
        resource.list(contextMock, uriInfoMock, factoryMock, authMock);
    }

    @Test(expected = StateAccessException.class)
    public void testListDataAccessError() throws Exception {
        doReturn(true).when(authMock).isAdmin(contextMock);
        doThrow(StateAccessException.class).when(daoMock).list();
        resource.list(contextMock, uriInfoMock, factoryMock, authMock);
    }

    @Test(expected = UnknownRestApiException.class)
    public void testListUnknownError() throws Exception {
        doReturn(true).when(authMock).isAdmin(contextMock);
        doThrow(RuntimeException.class).when(daoMock).list();
        resource.list(contextMock, uriInfoMock, factoryMock, authMock);
    }

    @Test
    public void testListSuccess() throws Exception {
        doReturn(true).when(authMock).isAdmin(contextMock);
        List<Vif> vifs = getVifs();
        doReturn(vifs).when(daoMock).list();

        List<Vif> newVifs = resource.list(contextMock, uriInfoMock,
                factoryMock, authMock);

        verify(uriInfoMock, times(vifs.size())).getBaseUri();
        Assert.assertEquals(vifs, newVifs);
    }
}
