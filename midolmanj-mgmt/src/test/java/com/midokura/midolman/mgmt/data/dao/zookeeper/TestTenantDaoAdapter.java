/*
 * @(#)TestTenantDaoAdapter        1.6 12/01/10
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import junit.framework.Assert;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.mgmt.data.zookeeper.op.TenantOpService;

public class TestTenantDaoAdapter {

    private TenantZkDao daoMock = null;
    private TenantOpService opServiceMock = null;
    private TenantDaoAdapter adapter = null;
    private RouterDao routerDaoMock = null;
    private BridgeDao bridgeDaoMock = null;
    private ChainDao chainDaoMock = null;

    private static List<Op> createTestPersistentCreateOps() {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op
                .create("/foo", new byte[] { 0 }, null, CreateMode.PERSISTENT));
        ops.add(Op
                .create("/bar", new byte[] { 1 }, null, CreateMode.PERSISTENT));
        ops.add(Op
                .create("/baz", new byte[] { 2 }, null, CreateMode.PERSISTENT));
        return ops;
    }

    private static List<Op> createTestDeleteOps() {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete("/foo", -1));
        ops.add(Op.delete("/bar", -1));
        ops.add(Op.delete("/baz", -1));
        return ops;
    }

    private static Set<String> createTestIds(int count) {
        Set<String> ids = new TreeSet<String>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID().toString());
        }
        return ids;
    }

    @Before
    public void setUp() throws Exception {
        daoMock = mock(TenantZkDao.class);
        opServiceMock = mock(TenantOpService.class);
        chainDaoMock = mock(ChainDao.class);
        routerDaoMock = mock(RouterDao.class);
        bridgeDaoMock = mock(BridgeDao.class);
        adapter = spy(new TenantDaoAdapter(daoMock, opServiceMock,
                bridgeDaoMock, routerDaoMock, chainDaoMock));
    }

    @Test
    public void testCreateWithNoIdSuccess() throws Exception {
        Tenant tenant = new Tenant(null);
        List<Op> ops = createTestPersistentCreateOps();
        doReturn(ops).when(opServiceMock).buildCreate(anyString());

        String newId = adapter.create(tenant);

        Assert.assertEquals(tenant.getId(), newId);
        verify(daoMock, times(1)).multi(ops);
    }

    @Test
    public void testCreateWithIdSuccess() throws Exception {
        String id = "foo";
        Tenant tenant = new Tenant(id);
        List<Op> ops = createTestPersistentCreateOps();
        doReturn(ops).when(opServiceMock).buildCreate(anyString());

        String newId = adapter.create(tenant);

        Assert.assertEquals(tenant.getId(), newId);
        verify(daoMock, times(1)).multi(ops);
    }

    @Test
    public void testDeleteSuccess() throws Exception {
        String id = "foo";
        List<Op> ops = createTestDeleteOps();
        doReturn(ops).when(opServiceMock).buildDelete(id);

        adapter.delete(id);

        verify(daoMock, times(1)).multi(ops);
    }

    @Test
    public void testGetSuccess() throws Exception {
        String id = "foo";

        Tenant tenant = adapter.get(id);

        Assert.assertEquals(id, tenant.getId());
    }

    @Test
    public void testListSuccess() throws Exception {

        Set<String> ids = createTestIds(3);

        doReturn(ids).when(daoMock).getIds();
        for (String id : ids) {
            Tenant tenant = new Tenant(id);
            doReturn(tenant).when(adapter).get(id);
        }

        List<Tenant> tenants = adapter.list();

        Assert.assertEquals(3, tenants.size());
        for (int i = 0; i < tenants.size(); i++) {
            Assert.assertTrue(ids.contains(tenants.get(i).getId()));
        }
    }

    @Test
    public void testGetByRouterPortSuccess() throws Exception {
        UUID portId = UUID.randomUUID();
        String id = "foo";
        Router router = new Router(UUID.randomUUID(), "bar", id);
        Tenant tenant = new Tenant(id);

        doReturn(router).when(routerDaoMock).getByPort(portId);
        doReturn(tenant).when(adapter).get(router.getTenantId());

        Tenant tenantResult = adapter.getByPort(portId);

        Assert.assertEquals(tenant, tenantResult);
    }

    @Test
    public void testGetByBridgePortSuccess() throws Exception {
        UUID portId = UUID.randomUUID();
        String id = "foo";
        Bridge bridge = new Bridge(UUID.randomUUID(), "bar", id);
        Tenant tenant = new Tenant(id);

        doReturn(null).when(routerDaoMock).getByPort(portId);
        doReturn(bridge).when(bridgeDaoMock).getByPort(portId);
        doReturn(tenant).when(adapter).get(bridge.getTenantId());

        Tenant tenantResult = adapter.getByPort(portId);

        Assert.assertEquals(tenant, tenantResult);
    }

    @Test
    public void testGetByAdRouteSuccess() throws Exception {
        UUID adRouteId = UUID.randomUUID();
        String id = "foo";
        Router router = new Router(UUID.randomUUID(), "bar", id);
        Tenant tenant = new Tenant(id);

        doReturn(router).when(routerDaoMock).getByAdRoute(adRouteId);
        doReturn(tenant).when(adapter).get(router.getTenantId());

        Tenant tenantResult = adapter.getByAdRoute(adRouteId);

        Assert.assertEquals(tenant, tenantResult);
    }

    @Test
    public void testGetByBgpSuccess() throws Exception {
        UUID bgpId = UUID.randomUUID();
        String id = "foo";
        Router router = new Router(UUID.randomUUID(), "bar", id);
        Tenant tenant = new Tenant(id);

        doReturn(router).when(routerDaoMock).getByBgp(bgpId);
        doReturn(tenant).when(adapter).get(router.getTenantId());

        Tenant tenantResult = adapter.getByBgp(bgpId);

        Assert.assertEquals(tenant, tenantResult);
    }

    @Test
    public void testGetByVpnSuccess() throws Exception {
        UUID vpnId = UUID.randomUUID();
        String id = "foo";
        Router router = new Router(UUID.randomUUID(), "bar", id);
        Tenant tenant = new Tenant(id);

        doReturn(router).when(routerDaoMock).getByVpn(vpnId);
        doReturn(tenant).when(adapter).get(router.getTenantId());

        Tenant tenantResult = adapter.getByVpn(vpnId);

        Assert.assertEquals(tenant, tenantResult);
    }

    @Test
    public void testGetByRouteSuccess() throws Exception {
        UUID routeId = UUID.randomUUID();
        String id = "foo";
        Router router = new Router(UUID.randomUUID(), "bar", id);
        Tenant tenant = new Tenant(id);

        doReturn(router).when(routerDaoMock).getByRoute(routeId);
        doReturn(tenant).when(adapter).get(router.getTenantId());

        Tenant tenantResult = adapter.getByRoute(routeId);

        Assert.assertEquals(tenant, tenantResult);
    }

    @Test
    public void testGetByRuleSuccess() throws Exception {
        Tenant tenant = new Tenant("foo");
        Chain chain = new Chain(UUID.randomUUID(), tenant.getId(), "fooChain");

        UUID ruleId = UUID.randomUUID();
        doReturn(chain).when(chainDaoMock).getByRule(ruleId);
        doReturn(tenant).when(adapter).get(chain.getTenantId());

        Tenant tenantResult = adapter.getByRule(ruleId);
        Assert.assertEquals(tenant, tenantResult);
    }

    @Test
    public void testGetByChainSuccess() throws Exception {
        Tenant tenant = new Tenant("foo");
        Chain chain = new Chain(UUID.randomUUID(), tenant.getId(), "fooChain");

        doReturn(chain).when(adapter).getByChain(chain.getId());
        doReturn(tenant).when(adapter).get(chain.getTenantId());

        Tenant tenantResult = adapter.getByChain(chain.getId());
        Assert.assertEquals(tenant, tenantResult);
    }

    @Test
    public void testGetByRouterSuccess() throws Exception {
        String id = "foo";
        Router router = new Router(UUID.randomUUID(), "bar", id);
        Tenant tenant = new Tenant(id);

        doReturn(router).when(routerDaoMock).get(router.getId());
        doReturn(tenant).when(adapter).get(router.getTenantId());

        Tenant tenantResult = adapter.getByRouter(router.getId());

        Assert.assertEquals(tenant, tenantResult);
    }

    @Test
    public void testGetByBridgeSuccess() throws Exception {
        String id = "foo";
        Bridge bridge = new Bridge(UUID.randomUUID(), "bar", id);
        Tenant tenant = new Tenant(id);

        doReturn(bridge).when(bridgeDaoMock).get(bridge.getId());
        doReturn(tenant).when(adapter).get(bridge.getTenantId());

        Tenant tenantResult = adapter.getByBridge(bridge.getId());

        Assert.assertEquals(tenant, tenantResult);
    }
}
