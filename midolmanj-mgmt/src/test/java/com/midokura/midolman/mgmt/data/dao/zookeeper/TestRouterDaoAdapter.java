/*
 * @(#)TestRouterDaoAdapter        1.6 12/01/10
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import static org.mockito.Matchers.any;
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

import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dao.RouteDao;
import com.midokura.midolman.mgmt.data.dto.BridgePort;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.op.RouterOpService;

public class TestRouterDaoAdapter {

    private RouterZkDao daoMock = null;
    private RouterOpService opServiceMock = null;
    private ChainDao chainDaoMock = null;
    private PortDao portDaoMock = null;
    private RouteDao routeDaoMock = null;
    private RouterDaoAdapter adapter = null;

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

    private static List<Op> createTestUpdateOps() {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.setData("/foo", new byte[] { 0 }, -1));
        ops.add(Op.setData("/bar", new byte[] { 1 }, -1));
        ops.add(Op.setData("/baz", new byte[] { 2 }, -1));
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
        daoMock = mock(RouterZkDao.class);
        opServiceMock = mock(RouterOpService.class);
        chainDaoMock = mock(ChainDao.class);
        portDaoMock = mock(PortDao.class);
        routeDaoMock = mock(RouteDao.class);
        adapter = spy(new RouterDaoAdapter(daoMock, opServiceMock,
                chainDaoMock, portDaoMock, routeDaoMock));
    }

    @Test
    public void testCreateWithNoIdSuccess() throws Exception {
        Router router = new Router(null, "foo", "bar");
        List<Op> ops = createTestPersistentCreateOps();
        doReturn(ops).when(opServiceMock).buildCreate(any(UUID.class),
                any(RouterMgmtConfig.class), any(RouterNameMgmtConfig.class));

        UUID newId = adapter.create(router);

        Assert.assertEquals(router.getId(), newId);
        verify(daoMock, times(1)).multi(ops);
    }

    @Test
    public void testCreateWithIdSuccess() throws Exception {
        Router router = new Router(UUID.randomUUID(), "foo", "bar");
        List<Op> ops = createTestPersistentCreateOps();
        doReturn(ops).when(opServiceMock).buildCreate(any(UUID.class),
                any(RouterMgmtConfig.class), any(RouterNameMgmtConfig.class));

        UUID newId = adapter.create(router);

        Assert.assertEquals(router.getId(), newId);
        verify(daoMock, times(1)).multi(ops);
    }

    @Test
    public void testDeleteSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        List<Op> ops = createTestDeleteOps();
        doReturn(ops).when(opServiceMock).buildDelete(id, true);

        adapter.delete(id);

        verify(daoMock, times(1)).multi(ops);
    }

    @Test
    public void testGetSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        RouterMgmtConfig mgmtConfig = new RouterMgmtConfig("foo", "bar");

        doReturn(mgmtConfig).when(daoMock).getMgmtData(id);
        doReturn(true).when(daoMock).exists(id);

        Router router = adapter.get(id);

        Assert.assertEquals(id, router.getId());
        Assert.assertEquals(mgmtConfig.tenantId, router.getTenantId());
        Assert.assertEquals(mgmtConfig.name, router.getName());
    }

    @Test
    public void testGetNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(false).when(daoMock).exists(id);

        Router router = adapter.get(id);

        Assert.assertNull(router);
    }

    @Test
    public void testListSuccess() throws Exception {

        Set<String> ids = createTestIds(3);
        String tenantId = "foo";

        doReturn(ids).when(daoMock).getIds(tenantId);
        for (String id : ids) {
            UUID uuid = UUID.fromString(id);
            Router router = new Router(uuid, id, tenantId);
            doReturn(router).when(adapter).get(uuid);
        }

        List<Router> routers = adapter.list(tenantId);

        Assert.assertEquals(3, routers.size());
        for (int i = 0; i < routers.size(); i++) {
            Assert.assertTrue(ids.contains(routers.get(i).getId().toString()));
            Assert.assertEquals(tenantId, routers.get(i).getTenantId());
        }
    }

    @Test
    public void testGetByPortSuccess() throws Exception {
        Port port = new MaterializedRouterPort(UUID.randomUUID(),
                UUID.randomUUID(), null);
        Router router = new Router(port.getDeviceId(), "foo", "bar");

        doReturn(port).when(portDaoMock).get(port.getId());
        doReturn(router).when(adapter).get(router.getId());

        Router routerResult = adapter.getByPort(port.getId());

        Assert.assertEquals(router, routerResult);
    }

    @Test
    public void testGetByPortWrongPortTypeSuccess() throws Exception {
        Port port = new BridgePort(UUID.randomUUID(), UUID.randomUUID(), null);

        doReturn(port).when(portDaoMock).get(port.getId());

        Router router = adapter.getByPort(port.getId());

        Assert.assertNull(router);
    }

    @Test
    public void testUpdateSuccess() throws Exception {

        Router router = new Router(UUID.randomUUID(), "foo", "bar");
        List<Op> ops = createTestUpdateOps();
        doReturn(ops).when(opServiceMock).buildUpdate(router);
        adapter.update(router);

        verify(daoMock, times(1)).multi(ops);
    }

    @Test
    public void testGetByAdRouteSuccess() throws Exception {
        UUID adRouteId = UUID.randomUUID();
        Port port = new MaterializedRouterPort(UUID.randomUUID(),
                UUID.randomUUID(), null);
        Router router = new Router(UUID.randomUUID(), "foo", "bar");

        doReturn(port).when(portDaoMock).getByAdRoute(adRouteId);
        doReturn(router).when(adapter).get(port.getDeviceId());

        Router routerResult = adapter.getByAdRoute(adRouteId);

        Assert.assertEquals(router, routerResult);
    }

    @Test
    public void testGetByBgpSuccess() throws Exception {
        UUID bgpId = UUID.randomUUID();
        Port port = new MaterializedRouterPort(UUID.randomUUID(),
                UUID.randomUUID(), null);
        Router router = new Router(UUID.randomUUID(), "foo", "bar");

        doReturn(port).when(portDaoMock).getByBgp(bgpId);
        doReturn(router).when(adapter).get(port.getDeviceId());

        Router routerResult = adapter.getByBgp(bgpId);

        Assert.assertEquals(router, routerResult);
    }

    @Test
    public void testGetByVpnSuccess() throws Exception {
        UUID vpnId = UUID.randomUUID();
        Port port = new MaterializedRouterPort(UUID.randomUUID(),
                UUID.randomUUID(), null);
        Router router = new Router(UUID.randomUUID(), "foo", "bar");

        doReturn(port).when(portDaoMock).getByVpn(vpnId);
        doReturn(router).when(adapter).get(port.getDeviceId());

        Router routerResult = adapter.getByVpn(vpnId);

        Assert.assertEquals(router, routerResult);
    }

    @Test
    public void testGetByRouteSuccess() throws Exception {
        Route route = new Route();
        route.setId(UUID.randomUUID());

        Router router = new Router(UUID.randomUUID(), "foo", "bar");

        doReturn(route).when(routeDaoMock).get(route.getId());
        doReturn(router).when(adapter).get(route.getRouterId());

        Router routerResult = adapter.getByRoute(route.getId());

        Assert.assertEquals(router, routerResult);
    }
}
