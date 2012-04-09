/*
 * @(#)TestTenantResource        1.6 12/01/16
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
import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.TenantDao;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.mgmt.rest_api.jaxrs.ForbiddenHttpException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.StateAccessException;

public class TestTenantResource {

    private SecurityContext contextMock = null;
    private DaoFactory factoryMock = null;
    private Authorizer authMock = null;
    private UriInfo uriInfoMock = null;
    private TenantResource resource = null;
    private TenantDao daoMock = null;

    @Before
    public void setUp() throws Exception {
        this.contextMock = mock(SecurityContext.class);
        this.factoryMock = mock(DaoFactory.class);
        this.authMock = mock(Authorizer.class);
        this.daoMock = mock(TenantDao.class);
        this.uriInfoMock = mock(UriInfo.class);
        this.resource = new TenantResource();

        doReturn(daoMock).when(factoryMock).getTenantDao();
    }

    private Tenant getTenant(URI baseUri) {
        Tenant tenant = new Tenant();
        if (baseUri != null) {
            tenant.setBaseUri(baseUri);
        }
        return tenant;
    }

    private List<Tenant> getTenants() {
        List<Tenant> tenants = new ArrayList<Tenant>();
        tenants.add(new Tenant(UUID.randomUUID().toString()));
        tenants.add(new Tenant(UUID.randomUUID().toString()));
        tenants.add(new Tenant(UUID.randomUUID().toString()));
        return tenants;
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testCreateUnauthorized() throws Exception {
        Tenant tenant = getTenant(null);
        doReturn(false).when(authMock).isAdmin(contextMock);
        resource.create(tenant, contextMock, uriInfoMock, factoryMock, authMock);
    }

    @Test(expected = StateAccessException.class)
    public void testCreateDataAccessError() throws Exception {
        Tenant tenant = getTenant(null);
        doReturn(true).when(authMock).isAdmin(contextMock);
        doThrow(StateAccessException.class).when(daoMock).create(tenant);
        resource.create(tenant, contextMock, uriInfoMock, factoryMock, authMock);
    }

    @Test
    public void testCreateSuccess() throws Exception {
        String id = UUID.randomUUID().toString();
        URI uri = URI.create("http://foo.com");
        Tenant tenant = getTenant(null);
        doReturn(true).when(authMock).isAdmin(contextMock);
        doReturn(id).when(daoMock).create(tenant);
        doReturn(uri).when(uriInfoMock).getBaseUri();
        Response resp = resource.create(tenant, contextMock, uriInfoMock,
                factoryMock, authMock);

        Assert.assertEquals(Response.Status.CREATED.getStatusCode(),
                resp.getStatus());
    }

    @Test
    public void testDeleteSuccess() throws Exception {
        String id = UUID.randomUUID().toString();
        doReturn(true).when(authMock).isAdmin(contextMock);
        resource.delete(id, contextMock, factoryMock, authMock);
        verify(daoMock, times(1)).delete(id);
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testDeleteUnauthorized() throws Exception {
        String id = UUID.randomUUID().toString();
        doReturn(false).when(authMock).isAdmin(contextMock);
        resource.delete(id, contextMock, factoryMock, authMock);
    }

    @Test
    public void testDeleteNonExistentData() throws Exception {
        String id = UUID.randomUUID().toString();
        doReturn(true).when(authMock).isAdmin(contextMock);
        doThrow(NoStatePathException.class).when(daoMock).delete(id);
        resource.delete(id, contextMock, factoryMock, authMock);
    }

    @Test(expected = StateAccessException.class)
    public void testDeleteDataAccessError() throws Exception {
        String id = UUID.randomUUID().toString();
        doReturn(true).when(authMock).isAdmin(contextMock);
        doThrow(StateAccessException.class).when(daoMock).delete(id);
        resource.delete(id, contextMock, factoryMock, authMock);
    }

    @Test
    public void testGetSuccess() throws Exception {
        String id = UUID.randomUUID().toString();
        doReturn(true).when(authMock).tenantAuthorized(contextMock,
                AuthAction.READ, id);
        URI uri = URI.create("http://foo.com");
        Tenant tenant = getTenant(null);
        doReturn(tenant).when(daoMock).get(id);
        doReturn(uri).when(uriInfoMock).getBaseUri();

        Tenant t = resource.get(id, contextMock, uriInfoMock, factoryMock,
                authMock);

        Assert.assertEquals(tenant, t);
        Assert.assertEquals(uri, t.getBaseUri());
    }

    @Test(expected = ForbiddenHttpException.class)
    public void testGetUnauthorized() throws Exception {
        String id = UUID.randomUUID().toString();
        doReturn(false).when(authMock).tenantAuthorized(contextMock,
                AuthAction.READ, id);
        resource.get(id, contextMock, uriInfoMock, factoryMock, authMock);
    }

    @Test(expected = StateAccessException.class)
    public void testGetDataAccessError() throws Exception {
        String id = UUID.randomUUID().toString();
        doReturn(true).when(authMock).tenantAuthorized(contextMock,
                AuthAction.READ, id);
        doThrow(StateAccessException.class).when(daoMock).get(id);
        resource.get(id, contextMock, uriInfoMock, factoryMock, authMock);
    }

    @Test(expected = ForbiddenHttpException.class)
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

    @Test
    public void testListSuccess() throws Exception {
        doReturn(true).when(authMock).isAdmin(contextMock);
        List<Tenant> tenants = getTenants();
        doReturn(tenants).when(daoMock).list();

        List<Tenant> newTenants = resource.list(contextMock, uriInfoMock,
                factoryMock, authMock);

        verify(uriInfoMock, times(tenants.size())).getBaseUri();
        Assert.assertEquals(tenants, newTenants);
    }
}
