/*
 * @(#)TestPortOpBuilder        1.6 12/1/6
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

public class TestPortOpBuilder {

    private PortOpPathBuilder pathBuilderMock = null;
    private PortZkDao zkDaoMock = null;
    private PortOpBuilder builder = null;
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
        this.pathBuilderMock = mock(PortOpPathBuilder.class);
        this.zkDaoMock = mock(PortZkDao.class);
        this.builder = new PortOpBuilder(this.pathBuilderMock, this.zkDaoMock);
    }

    @Test
    public void TestBuildCreatePortLinkSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        PortConfig config = new LogicalRouterPortConfig();
        PortConfig peerConfig = new LogicalRouterPortConfig();

        // Mock the path builder
        when(pathBuilderMock.getPortCreateOp(id, null)).thenReturn(
                dummyCreateOp0);
        when(pathBuilderMock.getPortCreateOp(peerId, null)).thenReturn(
                dummyCreateOp1);
        when(
                pathBuilderMock.getPortLinkCreateOps(id, config, peerId,
                        peerConfig)).thenReturn(dummyCreateOps);

        List<Op> ops = builder.buildCreateLink(id, config, peerId, peerConfig);

        Assert.assertEquals(5, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
        Assert.assertEquals(dummyCreateOp1, ops.get(1));
        Assert.assertEquals(dummyCreateOp0, ops.get(2));
        Assert.assertEquals(dummyCreateOp1, ops.get(3));
        Assert.assertEquals(dummyCreateOp2, ops.get(4));
    }

    @Test
    public void TestBuildCreatePortSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        PortConfig config = new LogicalRouterPortConfig();
        PortMgmtConfig mgmtConfig = new PortMgmtConfig();

        // Mock the path builder
        when(pathBuilderMock.getPortCreateOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp0);
        when(pathBuilderMock.getPortCreateOps(id, config)).thenReturn(
                dummyCreateOps);

        List<Op> ops = builder.buildCreate(id, config, mgmtConfig);

        Assert.assertEquals(4, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
        Assert.assertEquals(dummyCreateOp0, ops.get(1));
        Assert.assertEquals(dummyCreateOp1, ops.get(2));
        Assert.assertEquals(dummyCreateOp2, ops.get(3));
    }

    @Test
    public void TestBuildDeleteLinkSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();

        // Mock the path builder
        when(pathBuilderMock.getPortDeleteOps(id)).thenReturn(dummyDeleteOps);
        when(pathBuilderMock.getPortDeleteOp(peerId))
                .thenReturn(dummyDeleteOp0);
        when(pathBuilderMock.getPortDeleteOp(id)).thenReturn(dummyDeleteOp1);

        List<Op> ops = builder.buildDeleteLink(id, peerId);

        Assert.assertEquals(5, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.get(0));
        Assert.assertEquals(dummyDeleteOp1, ops.get(1));
        Assert.assertEquals(dummyDeleteOp2, ops.get(2));
        Assert.assertEquals(dummyDeleteOp0, ops.get(3));
        Assert.assertEquals(dummyDeleteOp1, ops.get(4));
    }

    @Test
    public void TestBuildDeleteWithCascadeSuccess() throws Exception {
        UUID id = UUID.randomUUID();

        // Mock the path builder
        when(pathBuilderMock.getPortDeleteOps(id)).thenReturn(dummyDeleteOps);
        when(pathBuilderMock.getPortDeleteOp(id)).thenReturn(dummyDeleteOp0);

        List<Op> ops = builder.buildDelete(id, true);

        Assert.assertEquals(4, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.get(0));
        Assert.assertEquals(dummyDeleteOp1, ops.get(1));
        Assert.assertEquals(dummyDeleteOp2, ops.get(2));
        Assert.assertEquals(dummyDeleteOp0, ops.get(3));
    }

    @Test
    public void TestBuildDeleteWithNoCascadeSuccess() throws Exception {
        UUID id = UUID.randomUUID();

        // Mock the path builder
        when(pathBuilderMock.getPortDeleteOp(id)).thenReturn(dummyDeleteOp0);

        List<Op> ops = builder.buildDelete(id, false);

        Assert.assertEquals(1, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.get(0));
    }

    @Test
    public void TestBuildUpdatePortSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        PortMgmtConfig mgmtConfig = new PortMgmtConfig();

        // Mock the path builder
        when(pathBuilderMock.getPortSetDataOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp0);

        List<Op> ops = builder.buildUpdate(id, mgmtConfig);

        Assert.assertEquals(1, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
    }

    @Test
    public void TestBuildPlugSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID vifId = UUID.randomUUID();
        PortMgmtConfig mgmtConfig = new PortMgmtConfig();

        // Mock the path builder
        when(zkDaoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(pathBuilderMock.getPortSetDataOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp0);

        List<Op> ops = builder.buildPlug(id, vifId);

        Assert.assertEquals(1, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
        Assert.assertEquals(vifId, mgmtConfig.vifId);
    }

    @Test
    public void TestBuildUnPlugSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        PortMgmtConfig mgmtConfig = new PortMgmtConfig();
        mgmtConfig.vifId = UUID.randomUUID();

        // Mock the path builder
        when(zkDaoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(pathBuilderMock.getPortSetDataOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp0);

        List<Op> ops = builder.buildPlug(id, null);

        Assert.assertEquals(1, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
        Assert.assertNull(mgmtConfig.vifId);
    }

    @Test
    public void TestBuildBridgePortsDeleteSuccess() throws Exception {
        UUID bridgeId = UUID.randomUUID();

        // Mock the path builder
        when(zkDaoMock.getBridgePortIds(bridgeId)).thenReturn(dummyIds);
        when(pathBuilderMock.getPortDeleteOp(dummyId0)).thenReturn(
                dummyDeleteOp0);
        when(pathBuilderMock.getPortDeleteOp(dummyId1)).thenReturn(
                dummyDeleteOp1);
        when(pathBuilderMock.getPortDeleteOp(dummyId2)).thenReturn(
                dummyDeleteOp2);

        List<Op> ops = builder.buildBridgePortsDelete(bridgeId);

        Assert.assertEquals(3, ops.size());
        Assert.assertTrue(ops.contains(dummyDeleteOp0));
        Assert.assertTrue(ops.contains(dummyDeleteOp1));
        Assert.assertTrue(ops.contains(dummyDeleteOp2));
    }

    @Test
    public void TestBuildRouterPortsDeleteSuccess() throws Exception {
        UUID routerId = UUID.randomUUID();

        // Mock the path builder
        when(zkDaoMock.getRouterPortIds(routerId)).thenReturn(dummyIds);
        when(pathBuilderMock.getPortDeleteOp(dummyId0)).thenReturn(
                dummyDeleteOp0);
        when(pathBuilderMock.getPortDeleteOp(dummyId1)).thenReturn(
                dummyDeleteOp1);
        when(pathBuilderMock.getPortDeleteOp(dummyId2)).thenReturn(
                dummyDeleteOp2);

        List<Op> ops = builder.buildRouterPortsDelete(routerId);

        Assert.assertEquals(3, ops.size());
        Assert.assertTrue(ops.contains(dummyDeleteOp0));
        Assert.assertTrue(ops.contains(dummyDeleteOp1));
        Assert.assertTrue(ops.contains(dummyDeleteOp2));
    }
}
