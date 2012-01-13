/*
 * @(#)TestAuthorizer        1.6 12/1/11
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.auth;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.util.UUID;

import javax.ws.rs.core.SecurityContext;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.mgmt.data.dao.TenantDao;
import com.midokura.midolman.mgmt.data.dto.Tenant;

public class TestAuthorizer {

    private AuthChecker checkerMock = null;
    private TenantDao tenantDaoMock = null;
    private Authorizer authorizer = null;
    private SecurityContext contextMock = null;

    @Before
    public void setUp() throws Exception {
        this.checkerMock = mock(AuthChecker.class);
        this.tenantDaoMock = mock(TenantDao.class);
        this.authorizer = spy(new Authorizer(checkerMock, tenantDaoMock));
        this.contextMock = mock(SecurityContext.class);
    }

    @Test
    public void testIsAdminTrue() throws Exception {
        doReturn(true).when(checkerMock).isAdmin(contextMock);
        boolean result = authorizer.isAdmin(contextMock);
        Assert.assertTrue(result);
    }

    @Test
    public void testIsAdminFalse() throws Exception {
        doReturn(false).when(checkerMock).isAdmin(contextMock);
        boolean result = authorizer.isAdmin(contextMock);
        Assert.assertFalse(result);
    }

    @Test
    public void testAdRouteAuthorizedAdminWrite() throws Exception {
        doReturn(true).when(checkerMock).isAdmin(contextMock);
        boolean result = authorizer.adRouteAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testAdRouteAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByAdRoute(id);
        doReturn(true).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.adRouteAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testAdRouteAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByAdRoute(id);
        doReturn(false).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.adRouteAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testBgpAuthorizedAdminWrite() throws Exception {
        doReturn(true).when(checkerMock).isAdmin(contextMock);
        boolean result = authorizer.bgpAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testBgpAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByBgp(id);
        doReturn(true).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.bgpAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testBgpAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByBgp(id);
        doReturn(false).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.bgpAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testBridgeAuthorizedAdminWrite() throws Exception {
        doReturn(true).when(checkerMock).isAdmin(contextMock);
        boolean result = authorizer.bridgeAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testBridgeAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByBridge(id);
        doReturn(true).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.bridgeAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testBridgeAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByBridge(id);
        doReturn(false).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.bridgeAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testChainAuthorizedAdminWrite() throws Exception {
        doReturn(true).when(checkerMock).isAdmin(contextMock);
        boolean result = authorizer.chainAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testChainAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByChain(id);
        doReturn(true).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.chainAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testChainAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByChain(id);
        doReturn(false).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.chainAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testPortAuthorizedAdminWrite() throws Exception {
        doReturn(true).when(checkerMock).isAdmin(contextMock);
        boolean result = authorizer.portAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testPortAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByPort(id);
        doReturn(true).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.portAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testPortAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByPort(id);
        doReturn(false).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.portAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testRouteAuthorizedAdminWrite() throws Exception {
        doReturn(true).when(checkerMock).isAdmin(contextMock);
        boolean result = authorizer.routeAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testRouteAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByRoute(id);
        doReturn(true).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.routeAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testRouteAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByRoute(id);
        doReturn(false).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.routeAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testRouterAuthorizedAdminWrite() throws Exception {
        doReturn(true).when(checkerMock).isAdmin(contextMock);
        boolean result = authorizer.routerAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testRouterAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByRouter(id);
        doReturn(true).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.routerAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testRouterAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByRouter(id);
        doReturn(false).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.routerAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testRuleAuthorizedAdminWrite() throws Exception {
        doReturn(true).when(checkerMock).isAdmin(contextMock);
        boolean result = authorizer.ruleAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testRuleAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByRule(id);
        doReturn(true).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.ruleAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testRuleAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByRule(id);
        doReturn(false).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.ruleAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testTenantAuthorizedAdminWrite() throws Exception {
        doReturn(true).when(checkerMock).isAdmin(contextMock);
        boolean result = authorizer.tenantAuthorized(contextMock,
                AuthAction.WRITE, "foo");
        Assert.assertTrue(result);
    }

    @Test
    public void testTenantAuthorizedOwnerWrite() throws Exception {
        String id = "foo";

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(true).when(checkerMock).isUserPrincipal(contextMock, id);

        boolean result = authorizer.tenantAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testTenantAuthorizedNonOwnerWrite() throws Exception {
        String id = "foo";

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(false).when(checkerMock).isUserPrincipal(contextMock, id);

        boolean result = authorizer.tenantAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testVifAuthorizedWriteTrue() throws Exception {
        UUID portId = UUID.randomUUID();
        doReturn(true).when(authorizer).portAuthorized(contextMock,
                AuthAction.WRITE, portId);

        boolean result = authorizer.vifAuthorized(contextMock,
                AuthAction.WRITE, portId);

        Assert.assertTrue(result);
    }

    @Test
    public void testVifAuthorizedWriteFalse() throws Exception {
        UUID portId = UUID.randomUUID();
        doReturn(false).when(authorizer).portAuthorized(contextMock,
                AuthAction.WRITE, portId);

        boolean result = authorizer.vifAuthorized(contextMock,
                AuthAction.WRITE, portId);

        Assert.assertFalse(result);
    }

    @Test
    public void testVpnAuthorizedAdminWrite() throws Exception {
        doReturn(true).when(checkerMock).isAdmin(contextMock);
        boolean result = authorizer.vpnAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testVpnAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByVpn(id);
        doReturn(true).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.vpnAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testVpnAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isAdmin(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByVpn(id);
        doReturn(false).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.vpnAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testRouterLinkAuthorizedProvider() throws Exception {
        doReturn(true).when(checkerMock).isProvider(contextMock);
        boolean result = authorizer.routerLinkAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID(), UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testRouterLinkAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        doReturn(false).when(checkerMock).isProvider(contextMock);
        doReturn(tenant).when(tenantDaoMock).getByRouter(id);
        doReturn(false).when(checkerMock).isUserPrincipal(contextMock,
                tenant.getId());

        boolean result = authorizer.routerLinkAuthorized(contextMock,
                AuthAction.WRITE, id, UUID.randomUUID());

        Assert.assertFalse(result);
    }

    @Test
    public void testRouterLinkAuthorizedHalfOwnerWrite() throws Exception {
        Tenant tenant1 = new Tenant("foo");
        Tenant tenant2 = new Tenant("bar");
        UUID id = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();

        doReturn(false).when(checkerMock).isProvider(contextMock);
        doReturn(tenant1).when(tenantDaoMock).getByRouter(id);
        doReturn(true).when(checkerMock).isUserPrincipal(contextMock,
                tenant1.getId());
        doReturn(tenant2).when(tenantDaoMock).getByRouter(peerId);

        boolean result = authorizer.routerLinkAuthorized(contextMock,
                AuthAction.WRITE, id, peerId);

        Assert.assertFalse(result);
    }

    @Test
    public void testRouterLinkAuthorizedOwnerWrite() throws Exception {
        Tenant tenant1 = new Tenant("foo");
        Tenant tenant2 = new Tenant("foo");
        UUID id = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();

        doReturn(false).when(checkerMock).isProvider(contextMock);
        doReturn(tenant1).when(tenantDaoMock).getByRouter(id);
        doReturn(true).when(checkerMock).isUserPrincipal(contextMock,
                tenant1.getId());
        doReturn(tenant2).when(tenantDaoMock).getByRouter(peerId);

        boolean result = authorizer.routerLinkAuthorized(contextMock,
                AuthAction.WRITE, id, peerId);

        Assert.assertTrue(result);
    }
}
