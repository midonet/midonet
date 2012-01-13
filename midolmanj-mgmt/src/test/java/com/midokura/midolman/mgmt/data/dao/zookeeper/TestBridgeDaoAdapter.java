/*
 * @(#)TestBridgeDaoAdapter        1.6 12/01/10
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

import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.dto.BridgePort;
import com.midokura.midolman.mgmt.data.dto.MaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.dto.config.BridgeMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.BridgeNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.op.BridgeOpService;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;

public class TestBridgeDaoAdapter {

    private BridgeZkDao daoMock = null;
    private BridgeOpService opServiceMock = null;
    private PortDao portDaoMock = null;
    private BridgeDaoAdapter adapter = null;

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
        daoMock = mock(BridgeZkDao.class);
        opServiceMock = mock(BridgeOpService.class);
        portDaoMock = mock(PortDao.class);
        adapter = spy(new BridgeDaoAdapter(daoMock, opServiceMock, portDaoMock));
    }

    @Test
    public void testCreateWithNoIdSuccess() throws Exception {
        Bridge bridge = new Bridge(null, "foo", "bar");
        List<Op> ops = createTestPersistentCreateOps();
        doReturn(ops).when(opServiceMock).buildCreate(any(UUID.class),
                any(BridgeConfig.class), any(BridgeMgmtConfig.class),
                any(BridgeNameMgmtConfig.class));

        UUID newId = adapter.create(bridge);

        Assert.assertEquals(bridge.getId(), newId);
        verify(daoMock, times(1)).multi(ops);
    }

    @Test
    public void testCreateWithIdSuccess() throws Exception {
        Bridge bridge = new Bridge(UUID.randomUUID(), "foo", "bar");
        List<Op> ops = createTestPersistentCreateOps();
        doReturn(ops).when(opServiceMock).buildCreate(any(UUID.class),
                any(BridgeConfig.class), any(BridgeMgmtConfig.class),
                any(BridgeNameMgmtConfig.class));

        UUID newId = adapter.create(bridge);

        Assert.assertEquals(bridge.getId(), newId);
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
        BridgeMgmtConfig mgmtConfig = new BridgeMgmtConfig("foo", "bar");

        doReturn(mgmtConfig).when(daoMock).getMgmtData(id);

        Bridge bridge = adapter.get(id);

        Assert.assertEquals(id, bridge.getId());
        Assert.assertEquals(mgmtConfig.tenantId, bridge.getTenantId());
        Assert.assertEquals(mgmtConfig.name, bridge.getName());
    }

    @Test
    public void testListSuccess() throws Exception {

        Set<String> ids = createTestIds(3);
        String tenantId = "foo";

        doReturn(ids).when(daoMock).getIds(tenantId);
        for (String id : ids) {
            UUID uuid = UUID.fromString(id);
            Bridge bridge = new Bridge(uuid, id, tenantId);
            doReturn(bridge).when(adapter).get(uuid);
        }

        List<Bridge> bridges = adapter.list(tenantId);

        Assert.assertEquals(3, bridges.size());
        for (int i = 0; i < bridges.size(); i++) {
            Assert.assertTrue(ids.contains(bridges.get(i).getId().toString()));
            Assert.assertEquals(tenantId, bridges.get(i).getTenantId());
        }
    }

    @Test
    public void testGetByPortSuccess() throws Exception {
        Port port = new BridgePort(UUID.randomUUID(), UUID.randomUUID(), null);
        Bridge bridge = new Bridge(port.getDeviceId(), "foo", "bar");

        doReturn(port).when(portDaoMock).get(port.getId());
        doReturn(bridge).when(adapter).get(bridge.getId());

        Bridge bridgeResult = adapter.getByPort(port.getId());

        Assert.assertEquals(bridge, bridgeResult);
    }

    @Test
    public void testGetByPortWrongPortTypeSuccess() throws Exception {
        Port port = new MaterializedRouterPort(UUID.randomUUID(),
                UUID.randomUUID(), null);

        doReturn(port).when(portDaoMock).get(port.getId());

        Bridge bridge = adapter.getByPort(port.getId());

        Assert.assertNull(bridge);
    }

    @Test
    public void testUpdateSuccess() throws Exception {

        Bridge bridge = new Bridge(UUID.randomUUID(), "foo", "bar");
        List<Op> ops = createTestUpdateOps();
        doReturn(ops).when(opServiceMock).buildUpdate(bridge.getId(),
                bridge.getName());
        adapter.update(bridge);

        verify(daoMock, times(1)).multi(ops);
    }
}
