/*
 * @(#)TestChainOpBuilder        1.6 11/12/26
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import org.mockito.Mockito;

import com.midokura.midolman.mgmt.data.dao.zookeeper.ChainZkDao;
import com.midokura.midolman.mgmt.data.dto.config.ChainMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.ChainNameMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.ChainTable;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;

public class TestChainOpBuilder {

    private ChainZkDao zkDaoMock = null;
    private ChainOpPathBuilder pathBuilderMock = null;
    private ChainOpBuilder builder = null;
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
    private static String dummyId0 = UUID.randomUUID().toString();
    private static String dummyId1 = UUID.randomUUID().toString();
    private static String dummyId2 = UUID.randomUUID().toString();
    private static Set<String> dummyIds = null;
    static {
        dummyIds = new TreeSet<String>();
        dummyIds.add(dummyId0);
        dummyIds.add(dummyId1);
        dummyIds.add(dummyId2);
    }

    @Before
    public void setUp() throws Exception {
        this.zkDaoMock = mock(ChainZkDao.class);
        this.pathBuilderMock = mock(ChainOpPathBuilder.class);
        this.builder = new ChainOpBuilder(this.pathBuilderMock, this.zkDaoMock);
    }

    @Test
    public void TestBuildCreateSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        ChainConfig config = new ChainConfig();
        config.routerId = UUID.randomUUID();
        config.name = "foo";
        ChainMgmtConfig mgmtConfig = new ChainMgmtConfig();
        mgmtConfig.table = ChainTable.NAT;
        ChainNameMgmtConfig nameConfig = new ChainNameMgmtConfig();

        // Mock the path builder
        when(pathBuilderMock.getChainCreateOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp0);
        when(
                pathBuilderMock.getRouterTableChainCreateOp(config.routerId,
                        ChainTable.NAT, id)).thenReturn(dummyCreateOp1);
        when(
                pathBuilderMock.getRouterTableChainNameCreateOp(
                        config.routerId, ChainTable.NAT, config.name,
                        nameConfig)).thenReturn(dummyCreateOp2);
        when(pathBuilderMock.getChainCreateOps(id, config)).thenReturn(
                dummyCreateOps);

        List<Op> ops = builder.buildCreate(id, config, mgmtConfig, nameConfig);

        Assert.assertEquals(6, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
        Assert.assertEquals(dummyCreateOp1, ops.get(1));
        Assert.assertEquals(dummyCreateOp2, ops.get(2));
        Assert.assertEquals(dummyCreateOp0, ops.get(3));
        Assert.assertEquals(dummyCreateOp1, ops.get(4));
        Assert.assertEquals(dummyCreateOp2, ops.get(5));
    }

    @Test
    public void TestBuildDeleteWithCascadeSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        ChainConfig config = new ChainConfig();
        config.routerId = UUID.randomUUID();
        config.name = "foo";
        ChainMgmtConfig mgmtConfig = new ChainMgmtConfig();
        mgmtConfig.table = ChainTable.NAT;

        // Mock the path builder
        when(zkDaoMock.getData(id)).thenReturn(config);
        when(zkDaoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(pathBuilderMock.getChainDeleteOps(id)).thenReturn(dummyDeleteOps);
        when(
                pathBuilderMock.getRouterTableChainNameDeleteOp(
                        config.routerId, mgmtConfig.table, config.name))
                .thenReturn(dummyDeleteOp0);
        when(
                pathBuilderMock.getRouterTableChainDeleteOp(config.routerId,
                        mgmtConfig.table, id)).thenReturn(dummyDeleteOp1);
        when(pathBuilderMock.getChainDeleteOp(id)).thenReturn(dummyDeleteOp2);

        List<Op> ops = builder.buildDelete(id, true);
        verify(pathBuilderMock, times(1)).getChainDeleteOps(id);
        Assert.assertEquals(6, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.get(0));
        Assert.assertEquals(dummyDeleteOp1, ops.get(1));
        Assert.assertEquals(dummyDeleteOp2, ops.get(2));
        Assert.assertEquals(dummyDeleteOp0, ops.get(3));
        Assert.assertEquals(dummyDeleteOp1, ops.get(4));
        Assert.assertEquals(dummyDeleteOp2, ops.get(5));
    }

    @Test
    public void TestBuildDeleteWithNoCascadeSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        ChainConfig config = new ChainConfig();
        config.routerId = UUID.randomUUID();
        config.name = "foo";
        ChainMgmtConfig mgmtConfig = new ChainMgmtConfig();
        mgmtConfig.table = ChainTable.NAT;

        // Mock the path builder
        when(zkDaoMock.getData(id)).thenReturn(config);
        when(zkDaoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(
                pathBuilderMock.getRouterTableChainNameDeleteOp(
                        config.routerId, mgmtConfig.table, config.name))
                .thenReturn(dummyDeleteOp0);
        when(
                pathBuilderMock.getRouterTableChainDeleteOp(config.routerId,
                        mgmtConfig.table, id)).thenReturn(dummyDeleteOp1);
        when(pathBuilderMock.getChainDeleteOp(id)).thenReturn(dummyDeleteOp2);

        List<Op> ops = builder.buildDelete(id, false);
        verify(pathBuilderMock, never()).getChainDeleteOps(id);
        Assert.assertEquals(3, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.get(0));
        Assert.assertEquals(dummyDeleteOp1, ops.get(1));
        Assert.assertEquals(dummyDeleteOp2, ops.get(2));
    }

    @Test
    public void TestBuildDeleteRouterChainsSuccess() throws Exception {
        ChainConfig config = new ChainConfig();
        config.routerId = UUID.randomUUID();
        config.name = "foo";
        ChainMgmtConfig mgmtConfig = new ChainMgmtConfig();
        mgmtConfig.table = ChainTable.NAT;

        when(zkDaoMock.getIds(config.routerId, mgmtConfig.table)).thenReturn(
                dummyIds);
        when(zkDaoMock.getData(Mockito.any(UUID.class))).thenReturn(config);
        when(zkDaoMock.getMgmtData(Mockito.any(UUID.class))).thenReturn(
                mgmtConfig);

        builder.buildDeleteRouterChains(config.routerId, ChainTable.NAT);

        verify(pathBuilderMock, times(1)).getChainDeleteOps(
                UUID.fromString(dummyId0));
        verify(pathBuilderMock, times(1)).getChainDeleteOps(
                UUID.fromString(dummyId1));
        verify(pathBuilderMock, times(1)).getChainDeleteOps(
                UUID.fromString(dummyId2));
    }

    @Test
    public void TestBuildBuiltInChainsSuccess() throws Exception {

        ChainConfig config = new ChainConfig();
        config.routerId = UUID.randomUUID();
        config.name = "foo";
        ChainMgmtConfig mgmtConfig = new ChainMgmtConfig();
        mgmtConfig.table = ChainTable.NAT;
        ChainNameMgmtConfig nameConfig = new ChainNameMgmtConfig();

        when(
                zkDaoMock.constructChainConfig(Mockito.anyString(),
                        Mockito.any(UUID.class))).thenReturn(config);
        when(zkDaoMock.constructChainMgmtConfig(Mockito.any(ChainTable.class)))
                .thenReturn(mgmtConfig);
        when(zkDaoMock.constructChainNameMgmtConfig(Mockito.any(UUID.class)))
                .thenReturn(nameConfig);
        builder.buildBuiltInChains(config.routerId, mgmtConfig.table);

        // There should be two built-in chains
        verify(pathBuilderMock, times(2)).getChainCreateOp(
                Mockito.any(UUID.class), Mockito.any(ChainMgmtConfig.class));
        verify(pathBuilderMock, times(2)).getChainCreateOps(
                Mockito.any(UUID.class), Mockito.any(ChainConfig.class));
        verify(pathBuilderMock, times(2)).getRouterTableChainNameCreateOp(
                config.routerId, mgmtConfig.table, config.name, nameConfig);

    }
}
