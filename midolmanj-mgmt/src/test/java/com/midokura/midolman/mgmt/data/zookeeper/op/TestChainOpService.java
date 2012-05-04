/*
 * @(#)TestChainOpService        1.6 11/12/26
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
import com.midokura.midolman.state.ChainZkManager.ChainConfig;

public class TestChainOpService {

    private ChainZkDao zkDaoMock = null;
    private ChainOpBuilder opBuilderMock = null;
    private ChainOpService service = null;
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
        this.opBuilderMock = mock(ChainOpBuilder.class);
        this.service = new ChainOpService(this.opBuilderMock, this.zkDaoMock);
    }

    @Test
    public void testBuildCreateSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        ChainConfig config = new ChainConfig("foo");
        ChainMgmtConfig mgmtConfig =
                new ChainMgmtConfig(UUID.randomUUID().toString(), config.name);
        ChainNameMgmtConfig nameConfig = new ChainNameMgmtConfig(id);

        // Mock the path builder
        when(opBuilderMock.getChainCreateOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp0);
        when(
                opBuilderMock.getTenantChainCreateOp(mgmtConfig.tenantId, id))
                .thenReturn(dummyCreateOp1);
        when(
                opBuilderMock.getTenantChainNameCreateOp(mgmtConfig.tenantId,
                        config.name, nameConfig)).thenReturn(
                dummyCreateOp2);
        when(opBuilderMock.getChainCreateOps(id, config)).thenReturn(
                dummyCreateOps);

        List<Op> ops = service.buildCreate(id, config, mgmtConfig, nameConfig);

        Assert.assertEquals(6, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
        Assert.assertEquals(dummyCreateOp1, ops.get(1));
        Assert.assertEquals(dummyCreateOp2, ops.get(2));
        Assert.assertEquals(dummyCreateOp0, ops.get(3));
        Assert.assertEquals(dummyCreateOp1, ops.get(4));
        Assert.assertEquals(dummyCreateOp2, ops.get(5));
    }

    @Test
    public void testBuildDeleteWithCascadeSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        ChainConfig config = new ChainConfig("foo");
        ChainMgmtConfig mgmtConfig =
                new ChainMgmtConfig(UUID.randomUUID().toString(), config.name);

        // Mock the path builder
        when(zkDaoMock.getData(id)).thenReturn(config);
        when(zkDaoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(opBuilderMock.getChainDeleteOps(id)).thenReturn(dummyDeleteOps);
        when(
                opBuilderMock.getTenantChainNameDeleteOp(mgmtConfig.tenantId,
                        config.name)).thenReturn(
                dummyDeleteOp0);
        when(
                opBuilderMock.getTenantChainDeleteOp(mgmtConfig.tenantId, id))
                .thenReturn(dummyDeleteOp1);
        when(opBuilderMock.getChainDeleteOp(id)).thenReturn(dummyDeleteOp2);

        List<Op> ops = service.buildDelete(id, true);
        verify(opBuilderMock, times(1)).getChainDeleteOps(id);
        Assert.assertEquals(6, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.get(0));
        Assert.assertEquals(dummyDeleteOp1, ops.get(1));
        Assert.assertEquals(dummyDeleteOp2, ops.get(2));
        Assert.assertEquals(dummyDeleteOp0, ops.get(3));
        Assert.assertEquals(dummyDeleteOp1, ops.get(4));
        Assert.assertEquals(dummyDeleteOp2, ops.get(5));
    }

    @Test
    public void testBuildDeleteWithNoCascadeSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        ChainConfig config = new ChainConfig("foo");
        ChainMgmtConfig mgmtConfig =
                new ChainMgmtConfig(UUID.randomUUID().toString(), config.name);

        // Mock the path builder
        when(zkDaoMock.getData(id)).thenReturn(config);
        when(zkDaoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(
                opBuilderMock.getTenantChainNameDeleteOp(mgmtConfig.tenantId,
                        config.name)).thenReturn(
                dummyDeleteOp0);
        when(
                opBuilderMock.getTenantChainDeleteOp(mgmtConfig.tenantId, id))
                .thenReturn(dummyDeleteOp1);
        when(opBuilderMock.getChainDeleteOp(id)).thenReturn(dummyDeleteOp2);

        List<Op> ops = service.buildDelete(id, false);
        verify(opBuilderMock, never()).getChainDeleteOps(id);
        Assert.assertEquals(3, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.get(0));
        Assert.assertEquals(dummyDeleteOp1, ops.get(1));
        Assert.assertEquals(dummyDeleteOp2, ops.get(2));
    }

    @Test
    public void testBuildDeleteRouterChainsSuccess() throws Exception {
        ChainConfig config = new ChainConfig("foo");
        ChainMgmtConfig mgmtConfig =
                new ChainMgmtConfig(UUID.randomUUID().toString(), config.name);

        when(zkDaoMock.getIds(mgmtConfig.tenantId)).thenReturn(
                dummyIds);
        when(zkDaoMock.getData(Mockito.any(UUID.class))).thenReturn(config);
        when(zkDaoMock.getMgmtData(Mockito.any(UUID.class))).thenReturn(
                mgmtConfig);

        service.buildTenantChainsDelete(mgmtConfig.tenantId.toString());

        verify(opBuilderMock, times(3)).getTenantChainNameDeleteOp(
                mgmtConfig.tenantId, config.name);
        for (String id : dummyIds) {
            verify(opBuilderMock, times(1)).getTenantChainDeleteOp(
                    mgmtConfig.tenantId, UUID.fromString(id));
            verify(opBuilderMock, times(1)).getChainDeleteOp(
                    UUID.fromString(id));
        }
    }

    @Test
    public void testBuildBuiltInChainsSuccess() throws Exception {

        ChainConfig config = new ChainConfig();
        config.name = "foo";
        ChainMgmtConfig mgmtConfig = new ChainMgmtConfig();
        ChainNameMgmtConfig nameConfig = new ChainNameMgmtConfig();
        mgmtConfig.tenantId = UUID.randomUUID().toString();

        when(
                zkDaoMock.constructChainMgmtConfig(Mockito.anyString(),
                        Mockito.anyString())).thenReturn(mgmtConfig);
        when(zkDaoMock.constructChainNameMgmtConfig(Mockito.any(UUID.class)))
                .thenReturn(nameConfig);

        // There should be two built-in chains
        verify(opBuilderMock, times(2)).getChainCreateOp(
                Mockito.any(UUID.class), Mockito.any(ChainMgmtConfig.class));
        verify(opBuilderMock, times(2)).getChainCreateOps(
                Mockito.any(UUID.class), Mockito.any(ChainConfig.class));
        verify(opBuilderMock, times(2)).getTenantChainNameCreateOp(
                mgmtConfig.tenantId, config.name, nameConfig);

    }
}
