/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.auth;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.UUID;

import javax.ws.rs.core.SecurityContext;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import com.midokura.midolman.mgmt.data.dao.TenantDao;
import com.midokura.midolman.mgmt.data.dto.Tenant;

@PrepareForTest(AuthChecker.class)
@RunWith(PowerMockRunner.class)
public class TestSimpleAuthorizer {

    private TenantDao tenantDaoMock = null;
    private SimpleAuthorizer authorizer = null;
    private SecurityContext contextMock = null;

    @Before
    public void setUp() throws Exception {
        this.tenantDaoMock = mock(TenantDao.class);
        this.authorizer = spy(new SimpleAuthorizer(tenantDaoMock));
        this.contextMock = mock(SecurityContext.class);
        PowerMockito.mockStatic(AuthChecker.class);
    }

    @Test
    public void testIsAdminTrue() throws Exception {
        when(AuthChecker.isAdmin(contextMock)).thenReturn(true);
        boolean result = authorizer.isAdmin(contextMock);
        Assert.assertTrue(result);
    }

    @Test
    public void testIsAdminFalse() throws Exception {
        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        boolean result = authorizer.isAdmin(contextMock);
        Assert.assertFalse(result);
    }

    @Test
    public void testAdRouteAuthorizedAdminWrite() throws Exception {
        when(AuthChecker.isAdmin(contextMock)).thenReturn(true);
        boolean result = authorizer.adRouteAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testAdRouteAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByAdRoute(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(true);

        boolean result = authorizer.adRouteAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testAdRouteAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByAdRoute(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(false);

        boolean result = authorizer.adRouteAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testBgpAuthorizedAdminWrite() throws Exception {
        when(AuthChecker.isAdmin(contextMock)).thenReturn(true);
        boolean result = authorizer.bgpAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testBgpAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByBgp(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(true);

        boolean result = authorizer.bgpAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testBgpAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByBgp(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(false);

        boolean result = authorizer.bgpAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testBridgeAuthorizedAdminWrite() throws Exception {
        when(AuthChecker.isAdmin(contextMock)).thenReturn(true);
        boolean result = authorizer.bridgeAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testBridgeAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByBridge(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(true);

        boolean result = authorizer.bridgeAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testBridgeAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByBridge(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(false);

        boolean result = authorizer.bridgeAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testChainAuthorizedAdminWrite() throws Exception {
        when(AuthChecker.isAdmin(contextMock)).thenReturn(true);
        boolean result = authorizer.chainAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testChainAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByChain(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(true);

        boolean result = authorizer.chainAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testChainAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByChain(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(false);

        boolean result = authorizer.chainAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testPortAuthorizedAdminWrite() throws Exception {
        when(AuthChecker.isAdmin(contextMock)).thenReturn(true);
        boolean result = authorizer.portAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testPortAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByPort(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(true);

        boolean result = authorizer.portAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testPortAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByPort(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(false);

        boolean result = authorizer.portAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testRouteAuthorizedAdminWrite() throws Exception {
        when(AuthChecker.isAdmin(contextMock)).thenReturn(true);
        boolean result = authorizer.routeAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testRouteAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByRoute(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(true);

        boolean result = authorizer.routeAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testRouteAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByRoute(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(false);

        boolean result = authorizer.routeAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testRouterAuthorizedAdminWrite() throws Exception {
        when(AuthChecker.isAdmin(contextMock)).thenReturn(true);
        boolean result = authorizer.routerAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testRouterAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByRouter(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(true);

        boolean result = authorizer.routerAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testRouterAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByRouter(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(false);

        boolean result = authorizer.routerAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testRuleAuthorizedAdminWrite() throws Exception {
        when(AuthChecker.isAdmin(contextMock)).thenReturn(true);
        boolean result = authorizer.ruleAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testRuleAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByRule(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(true);

        boolean result = authorizer.ruleAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testRuleAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByRule(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(false);

        boolean result = authorizer.ruleAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testTenantAuthorizedAdminWrite() throws Exception {
        when(AuthChecker.isAdmin(contextMock)).thenReturn(true);
        boolean result = authorizer.tenantAuthorized(contextMock,
                AuthAction.WRITE, "foo");
        Assert.assertTrue(result);
    }

    @Test
    public void testTenantAuthorizedOwnerWrite() throws Exception {
        String id = "foo";

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        when(AuthChecker.isUserPrincipal(contextMock, id)).thenReturn(true);

        boolean result = authorizer.tenantAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testTenantAuthorizedNonOwnerWrite() throws Exception {
        String id = "foo";

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        when(AuthChecker.isUserPrincipal(contextMock, id)).thenReturn(false);

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
        when(AuthChecker.isAdmin(contextMock)).thenReturn(true);
        boolean result = authorizer.vpnAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testVpnAuthorizedOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByVpn(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(true);

        boolean result = authorizer.vpnAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertTrue(result);
    }

    @Test
    public void testVpnAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByVpn(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(false);

        boolean result = authorizer.vpnAuthorized(contextMock,
                AuthAction.WRITE, id);

        Assert.assertFalse(result);
    }

    @Test
    public void testRouterLinkAuthorizedProvider() throws Exception {
        when(AuthChecker.isAdmin(contextMock)).thenReturn(true);
        boolean result = authorizer.routerLinkAuthorized(contextMock,
                AuthAction.WRITE, UUID.randomUUID(), UUID.randomUUID());
        Assert.assertTrue(result);
    }

    @Test
    public void testRouterLinkAuthorizedNonOwnerWrite() throws Exception {
        Tenant tenant = new Tenant("foo");
        UUID id = UUID.randomUUID();

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant).when(tenantDaoMock).getByRouter(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant.getId()))
                .thenReturn(false);

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

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant1).when(tenantDaoMock).getByRouter(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant1.getId()))
                .thenReturn(true);
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

        when(AuthChecker.isAdmin(contextMock)).thenReturn(false);
        doReturn(tenant1).when(tenantDaoMock).getByRouter(id);
        when(AuthChecker.isUserPrincipal(contextMock, tenant1.getId()))
                .thenReturn(true);
        doReturn(tenant2).when(tenantDaoMock).getByRouter(peerId);

        boolean result = authorizer.routerLinkAuthorized(contextMock,
                AuthAction.WRITE, id, peerId);

        Assert.assertTrue(result);
    }
}
