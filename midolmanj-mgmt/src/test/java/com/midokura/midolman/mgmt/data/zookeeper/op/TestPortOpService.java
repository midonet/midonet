/*
 * @(#)TestPortOpService        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

import com.midokura.midolman.mgmt.data.dao.zookeeper.PortZkDao;
import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;

public class TestPortOpService {

    private PortOpBuilder opBuilderMock = null;
    private PortZkDao zkDaoMock = null;
    private PortOpService service = null;
    private static final Op dummyCreateOp0 = Op.create("/foo",
            new byte[] { 0 }, null, CreateMode.PERSISTENT);
    private static final Op dummyCreateOp1 = Op.create("/bar",
            new byte[] { 1 }, null, CreateMode.PERSISTENT);
    private static final Op dummyCreateOp2 = Op.create("/baz",
            new byte[] { 2 }, null, CreateMode.PERSISTENT);
    private static final Op dummyDeleteOp0 = Op.delete("/foo", -1);;
    private static final Op dummyDeleteOp1 = Op.delete("/bar", -1);
    private static final Op dummyDeleteOp2 = Op.delete("/baz", -1);
    private static List<Op> dummyCreateOps = null;
    static {
        dummyCreateOps = new ArrayList<Op>();
        dummyCreateOps.add(dummyCreateOp0);
        dummyCreateOps.add(dummyCreateOp1);
        dummyCreateOps.add(dummyCreateOp2);
    }
    private static List<Op> dummyDeleteOps = null;
    static {
        dummyDeleteOps = new ArrayList<Op>();
        dummyDeleteOps.add(dummyDeleteOp0);
        dummyDeleteOps.add(dummyDeleteOp1);
        dummyDeleteOps.add(dummyDeleteOp2);
    }
    private static UUID dummyId0 = UUID.randomUUID();
    private static UUID dummyId1 = UUID.randomUUID();
    private static UUID dummyId2 = UUID.randomUUID();
    private static Set<UUID> dummyIds = null;
    static {
        dummyIds = new TreeSet<UUID>();
        dummyIds.add(dummyId0);
        dummyIds.add(dummyId1);
        dummyIds.add(dummyId2);
    }

    @Before
    public void setUp() throws Exception {
        this.opBuilderMock = mock(PortOpBuilder.class);
        this.zkDaoMock = mock(PortZkDao.class);
        this.service = new PortOpService(this.opBuilderMock, this.zkDaoMock);
    }

    @Test
    public void testBuildCreatePortLinkSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        PortConfig config = new LogicalRouterPortConfig();
        PortConfig peerConfig = new LogicalRouterPortConfig();

        // Mock the path builder
        when(opBuilderMock.getPortCreateOp(id, null))
                .thenReturn(dummyCreateOp0);
        when(opBuilderMock.getPortCreateOp(peerId, null)).thenReturn(
                dummyCreateOp1);
        when(opBuilderMock.getPortLinkCreateOps(id, config, peerId, peerConfig))
                .thenReturn(dummyCreateOps);

        List<Op> ops = service.buildCreateLink(id, config, peerId, peerConfig);

        Assert.assertEquals(5, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
        Assert.assertEquals(dummyCreateOp1, ops.get(1));
        Assert.assertEquals(dummyCreateOp0, ops.get(2));
        Assert.assertEquals(dummyCreateOp1, ops.get(3));
        Assert.assertEquals(dummyCreateOp2, ops.get(4));
    }

    @Test
    public void testBuildCreatePortSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        PortConfig config = new LogicalRouterPortConfig();
        PortMgmtConfig mgmtConfig = new PortMgmtConfig();

        // Mock the path builder
        when(opBuilderMock.getPortCreateOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp0);
        when(opBuilderMock.getPortCreateOps(id, config)).thenReturn(
                dummyCreateOps);

        List<Op> ops = service.buildCreate(id, config, mgmtConfig);

        Assert.assertEquals(4, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
        Assert.assertEquals(dummyCreateOp0, ops.get(1));
        Assert.assertEquals(dummyCreateOp1, ops.get(2));
        Assert.assertEquals(dummyCreateOp2, ops.get(3));
    }

    @Test
    public void testBuildDeleteLinkSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();

        // Mock the path builder
        when(opBuilderMock.getPortDeleteOps(id)).thenReturn(dummyDeleteOps);
        when(opBuilderMock.getPortDeleteOp(peerId)).thenReturn(dummyDeleteOp0);
        when(opBuilderMock.getPortDeleteOp(id)).thenReturn(dummyDeleteOp1);

        List<Op> ops = service.buildDeleteLink(id, peerId);

        Assert.assertEquals(5, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.get(0));
        Assert.assertEquals(dummyDeleteOp1, ops.get(1));
        Assert.assertEquals(dummyDeleteOp2, ops.get(2));
        Assert.assertEquals(dummyDeleteOp0, ops.get(3));
        Assert.assertEquals(dummyDeleteOp1, ops.get(4));
    }

    @Test
    public void testBuildDeleteWithCascadeSuccess() throws Exception {
        UUID id = UUID.randomUUID();

        // Mock the path builder
        when(opBuilderMock.getPortDeleteOps(id)).thenReturn(dummyDeleteOps);
        when(opBuilderMock.getPortDeleteOp(id)).thenReturn(dummyDeleteOp0);

        List<Op> ops = service.buildDelete(id, true);

        Assert.assertEquals(4, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.get(0));
        Assert.assertEquals(dummyDeleteOp1, ops.get(1));
        Assert.assertEquals(dummyDeleteOp2, ops.get(2));
        Assert.assertEquals(dummyDeleteOp0, ops.get(3));
    }

    @Test
    public void testBuildDeleteWithNoCascadeSuccess() throws Exception {
        UUID id = UUID.randomUUID();

        // Mock the path builder
        when(opBuilderMock.getPortDeleteOp(id)).thenReturn(dummyDeleteOp0);

        List<Op> ops = service.buildDelete(id, false);

        Assert.assertEquals(1, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.get(0));
    }

    @Test
    public void testBuildUpdatePortSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        PortMgmtConfig mgmtConfig = new PortMgmtConfig();

        // Mock the path builder
        when(opBuilderMock.getPortSetDataOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp0);

        List<Op> ops = service.buildUpdate(id, mgmtConfig);

        Assert.assertEquals(1, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
    }

    @Test
    public void testBuildPlugSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID vifId = UUID.randomUUID();
        PortMgmtConfig mgmtConfig = new PortMgmtConfig();

        // Mock the path builder
        when(zkDaoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(opBuilderMock.getPortSetDataOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp0);

        List<Op> ops = service.buildPlug(id, vifId);

        Assert.assertEquals(1, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
        Assert.assertEquals(vifId, mgmtConfig.vifId);
    }

    @Test
    public void testBuildUnPlugSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        PortMgmtConfig mgmtConfig = new PortMgmtConfig();
        mgmtConfig.vifId = UUID.randomUUID();

        // Mock the path builder
        when(zkDaoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(opBuilderMock.getPortSetDataOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp0);

        List<Op> ops = service.buildPlug(id, null);

        Assert.assertEquals(1, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
        Assert.assertNull(mgmtConfig.vifId);
    }

    @Test
    public void testBuildBridgePortsDeleteSuccess() throws Exception {
        UUID bridgeId = UUID.randomUUID();

        // Mock the path builder
        when(zkDaoMock.getBridgePortIds(bridgeId)).thenReturn(dummyIds);
        when(opBuilderMock.getPortDeleteOp(dummyId0))
                .thenReturn(dummyDeleteOp0);
        when(opBuilderMock.getPortDeleteOp(dummyId1))
                .thenReturn(dummyDeleteOp1);
        when(opBuilderMock.getPortDeleteOp(dummyId2))
                .thenReturn(dummyDeleteOp2);

        List<Op> ops = service.buildBridgePortsDelete(bridgeId);

        Assert.assertEquals(3, ops.size());
        Assert.assertTrue(ops.contains(dummyDeleteOp0));
        Assert.assertTrue(ops.contains(dummyDeleteOp1));
        Assert.assertTrue(ops.contains(dummyDeleteOp2));
    }

    @Test
    public void testBuildRouterPortsDeleteSuccess() throws Exception {
        UUID routerId = UUID.randomUUID();

        // Mock the path builder
        when(zkDaoMock.getRouterPortIds(routerId)).thenReturn(dummyIds);
        when(opBuilderMock.getPortDeleteOp(dummyId0))
                .thenReturn(dummyDeleteOp0);
        when(opBuilderMock.getPortDeleteOp(dummyId1))
                .thenReturn(dummyDeleteOp1);
        when(opBuilderMock.getPortDeleteOp(dummyId2))
                .thenReturn(dummyDeleteOp2);

        List<Op> ops = service.buildRouterPortsDelete(routerId);

        Assert.assertEquals(3, ops.size());
        Assert.assertTrue(ops.contains(dummyDeleteOp0));
        Assert.assertTrue(ops.contains(dummyDeleteOp1));
        Assert.assertTrue(ops.contains(dummyDeleteOp2));
    }
}
