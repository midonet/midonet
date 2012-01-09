/*
 * @(#)TestRouterOpBuilder        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import junit.framework.Assert;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.mgmt.data.dao.zookeeper.RouterZkDao;
import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.ChainTable;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;

public class TestRouterOpBuilder {

    private RouterOpPathBuilder pathBuilderMock = null;
    private RouterZkDao zkDaoMock = null;
    private PortOpBuilder portOpBuilderMock = null;
    private ChainOpBuilder chainOpBuilderMock = null;
    private RouterOpBuilder builder = null;
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

    @Before
    public void setUp() throws Exception {
        this.pathBuilderMock = mock(RouterOpPathBuilder.class);
        this.portOpBuilderMock = mock(PortOpBuilder.class);
        this.chainOpBuilderMock = mock(ChainOpBuilder.class);
        this.zkDaoMock = mock(RouterZkDao.class);
        this.builder = new RouterOpBuilder(this.pathBuilderMock,
                this.chainOpBuilderMock, this.portOpBuilderMock, this.zkDaoMock);
    }

    @Test
    public void TestBuildCreateRouterSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        RouterMgmtConfig mgmtConfig = new RouterMgmtConfig();
        mgmtConfig.tenantId = "foo";
        mgmtConfig.name = "bar";
        RouterNameMgmtConfig nameConfig = new RouterNameMgmtConfig();

        // Mock the path builder
        when(pathBuilderMock.getRouterCreateOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp0);
        when(pathBuilderMock.getRouterRoutersCreateOp(id)).thenReturn(
                dummyCreateOp1);
        when(pathBuilderMock.getRouterTablesCreateOp(id)).thenReturn(
                dummyCreateOp2);
        for (ChainTable chainTable : ChainTable.class.getEnumConstants()) {
            when(pathBuilderMock.getRouterTableCreateOp(id, chainTable))
                    .thenReturn(dummyCreateOp0);
            when(pathBuilderMock.getRouterTableChainsCreateOp(id, chainTable))
                    .thenReturn(dummyCreateOp1);
            when(
                    pathBuilderMock.getRouterTableChainNamesCreateOp(id,
                            chainTable)).thenReturn(dummyCreateOp2);
            when(chainOpBuilderMock.buildBuiltInChains(id, chainTable))
                    .thenReturn(dummyCreateOps);
        }

        when(pathBuilderMock.getTenantRouterCreateOp(mgmtConfig.tenantId, id))
                .thenReturn(dummyCreateOp0);
        when(
                pathBuilderMock.getTenantRouterNameCreateOp(
                        mgmtConfig.tenantId, mgmtConfig.name, nameConfig))
                .thenReturn(dummyCreateOp1);
        when(pathBuilderMock.getRouterCreateOps(id)).thenReturn(dummyCreateOps);

        List<Op> ops = builder.buildCreate(id, mgmtConfig, nameConfig);

        int chainNum = ChainTable.class.getEnumConstants().length;
        int chainOpNum = chainNum * 6;
        Assert.assertEquals(8 + chainOpNum, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.remove(0));
        Assert.assertEquals(dummyCreateOp1, ops.remove(0));
        Assert.assertEquals(dummyCreateOp2, ops.remove(0));
        for (int i = 0; i < chainNum; i++) {
            Assert.assertEquals(dummyCreateOp0, ops.remove(0));
            Assert.assertEquals(dummyCreateOp1, ops.remove(0));
            Assert.assertEquals(dummyCreateOp2, ops.remove(0));
            Assert.assertEquals(dummyCreateOp0, ops.remove(0));
            Assert.assertEquals(dummyCreateOp1, ops.remove(0));
            Assert.assertEquals(dummyCreateOp2, ops.remove(0));
        }
        Assert.assertEquals(dummyCreateOp0, ops.remove(0));
        Assert.assertEquals(dummyCreateOp1, ops.remove(0));
        Assert.assertEquals(dummyCreateOp0, ops.remove(0));
        Assert.assertEquals(dummyCreateOp1, ops.remove(0));
        Assert.assertEquals(dummyCreateOp2, ops.remove(0));
    }

    @Test
    public void TestBuildDeleteWithCascadeSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        RouterMgmtConfig mgmtConfig = new RouterMgmtConfig();
        mgmtConfig.tenantId = "foo";
        mgmtConfig.name = "bar";

        // Mock the path builder
        when(zkDaoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(pathBuilderMock.getRouterDeleteOps(id)).thenReturn(dummyDeleteOps);
        when(portOpBuilderMock.buildRouterPortsDelete(id)).thenReturn(
                dummyDeleteOps);
        when(
                pathBuilderMock.getTenantRouterNameDeleteOp(
                        mgmtConfig.tenantId, mgmtConfig.name)).thenReturn(
                dummyDeleteOp0);
        when(pathBuilderMock.getTenantRouterDeleteOp(mgmtConfig.tenantId, id))
                .thenReturn(dummyDeleteOp1);

        for (ChainTable chainTable : ChainTable.class.getEnumConstants()) {
            when(chainOpBuilderMock.buildDeleteRouterChains(id, chainTable))
                    .thenReturn(dummyDeleteOps);
            when(
                    pathBuilderMock.getRouterTableChainNamesDeleteOp(id,
                            chainTable)).thenReturn(dummyDeleteOp0);
            when(pathBuilderMock.getRouterTableChainsDeleteOp(id, chainTable))
                    .thenReturn(dummyDeleteOp1);
            when(pathBuilderMock.getRouterTableDeleteOp(id, chainTable))
                    .thenReturn(dummyDeleteOp2);
        }

        when(pathBuilderMock.getRouterTablesDeleteOp(id)).thenReturn(
                dummyDeleteOp0);
        when(pathBuilderMock.getRouterRoutersDeleteOp(id)).thenReturn(
                dummyDeleteOp1);
        when(pathBuilderMock.getRouterDeleteOp(id)).thenReturn(dummyDeleteOp2);

        List<Op> ops = builder.buildDelete(id, true);

        int chainNum = ChainTable.class.getEnumConstants().length;
        int chainOpNum = chainNum * 6;
        Assert.assertEquals(11 + chainOpNum, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp1, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp2, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp0, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp1, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp2, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp0, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp1, ops.remove(0));
        for (int i = 0; i < chainNum; i++) {
            Assert.assertEquals(dummyDeleteOp0, ops.remove(0));
            Assert.assertEquals(dummyDeleteOp1, ops.remove(0));
            Assert.assertEquals(dummyDeleteOp2, ops.remove(0));
            Assert.assertEquals(dummyDeleteOp0, ops.remove(0));
            Assert.assertEquals(dummyDeleteOp1, ops.remove(0));
            Assert.assertEquals(dummyDeleteOp2, ops.remove(0));
        }
        Assert.assertEquals(dummyDeleteOp0, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp1, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp2, ops.remove(0));

    }

    @Test
    public void TestBuildDeleteWithNoCascadeSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        RouterMgmtConfig mgmtConfig = new RouterMgmtConfig();
        mgmtConfig.tenantId = "foo";
        mgmtConfig.name = "bar";

        // Mock the path builder
        when(zkDaoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(portOpBuilderMock.buildRouterPortsDelete(id)).thenReturn(
                dummyDeleteOps);
        when(
                pathBuilderMock.getTenantRouterNameDeleteOp(
                        mgmtConfig.tenantId, mgmtConfig.name)).thenReturn(
                dummyDeleteOp0);
        when(pathBuilderMock.getTenantRouterDeleteOp(mgmtConfig.tenantId, id))
                .thenReturn(dummyDeleteOp1);

        for (ChainTable chainTable : ChainTable.class.getEnumConstants()) {
            when(chainOpBuilderMock.buildDeleteRouterChains(id, chainTable))
                    .thenReturn(dummyDeleteOps);
            when(
                    pathBuilderMock.getRouterTableChainNamesDeleteOp(id,
                            chainTable)).thenReturn(dummyDeleteOp0);
            when(pathBuilderMock.getRouterTableChainsDeleteOp(id, chainTable))
                    .thenReturn(dummyDeleteOp1);
            when(pathBuilderMock.getRouterTableDeleteOp(id, chainTable))
                    .thenReturn(dummyDeleteOp2);
        }

        when(pathBuilderMock.getRouterTablesDeleteOp(id)).thenReturn(
                dummyDeleteOp0);
        when(pathBuilderMock.getRouterRoutersDeleteOp(id)).thenReturn(
                dummyDeleteOp1);
        when(pathBuilderMock.getRouterDeleteOp(id)).thenReturn(dummyDeleteOp2);

        List<Op> ops = builder.buildDelete(id, false);

        int chainNum = ChainTable.class.getEnumConstants().length;
        int chainOpNum = chainNum * 6;
        Assert.assertEquals(8 + chainOpNum, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp1, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp2, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp0, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp1, ops.remove(0));
        for (int i = 0; i < chainNum; i++) {
            Assert.assertEquals(dummyDeleteOp0, ops.remove(0));
            Assert.assertEquals(dummyDeleteOp1, ops.remove(0));
            Assert.assertEquals(dummyDeleteOp2, ops.remove(0));
            Assert.assertEquals(dummyDeleteOp0, ops.remove(0));
            Assert.assertEquals(dummyDeleteOp1, ops.remove(0));
            Assert.assertEquals(dummyDeleteOp2, ops.remove(0));
        }
        Assert.assertEquals(dummyDeleteOp0, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp1, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp2, ops.remove(0));
    }

    @Test
    public void TestBuildUpdateSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        String name = "foo";
        RouterMgmtConfig mgmtConfig = new RouterMgmtConfig();
        mgmtConfig.tenantId = "bar";
        mgmtConfig.name = "baz";
        RouterNameMgmtConfig nameConfig = new RouterNameMgmtConfig();

        // Mock the path builder
        when(zkDaoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(zkDaoMock.getNameData(mgmtConfig.tenantId, mgmtConfig.name))
                .thenReturn(nameConfig);
        when(
                pathBuilderMock.getTenantRouterNameDeleteOp(
                        mgmtConfig.tenantId, mgmtConfig.name)).thenReturn(
                dummyCreateOp0);
        when(
                pathBuilderMock.getTenantRouterNameCreateOp(
                        mgmtConfig.tenantId, name, nameConfig)).thenReturn(
                dummyCreateOp1);
        when(pathBuilderMock.getRouterSetDataOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp2);

        List<Op> ops = builder.buildUpdate(id, name);

        Assert.assertEquals(3, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
        Assert.assertEquals(dummyCreateOp1, ops.get(1));
        Assert.assertEquals(dummyCreateOp2, ops.get(2));
        Assert.assertEquals(name, mgmtConfig.name);
    }

    @Test
    public void TestBuildLinkSuccess() throws Exception {
        UUID portId = UUID.randomUUID();
        PortConfig config = new LogicalRouterPortConfig();
        UUID peerPortId = UUID.randomUUID();
        PortConfig peerConfig = new LogicalRouterPortConfig();

        builder.buildLink(portId, config, peerPortId, peerConfig);

        verify(portOpBuilderMock, times(1)).buildCreateLink(portId, config,
                peerPortId, peerConfig);
    }

    @Test
    public void TestBuildUnlinkSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        PeerRouterConfig config = new PeerRouterConfig();
        config.portId = UUID.randomUUID();
        config.peerPortId = UUID.randomUUID();

        when(zkDaoMock.getRouterLinkData(id, peerId)).thenReturn(config);

        builder.buildUnlink(id, peerId);

        verify(portOpBuilderMock, times(1)).buildDeleteLink(config.portId,
                config.peerPortId);
        verify(pathBuilderMock, times(1)).getRouterRouterDeleteOp(id, peerId);
        verify(pathBuilderMock, times(1)).getRouterRouterDeleteOp(peerId, id);
    }
}
