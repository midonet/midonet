/*
 * @(#)TestRouterOpService        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
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

import com.midokura.midolman.mgmt.data.dao.zookeeper.RouterZkDao;
import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.ChainTable;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;

public class TestRouterOpService {

    private RouterOpBuilder opBuilderMock = null;
    private RouterZkDao zkDaoMock = null;
    private PortOpService portOpServiceMock = null;
    private ChainOpService chainOpServiceMock = null;
    private BridgeOpBuilder bridgeOpBuilderMock = null;
    private RouterOpService service = null;
    private static final Op dummyCreateOp0 = Op.create("/foo",
            new byte[] { 0 }, null, CreateMode.PERSISTENT);
    private static final Op dummyCreateOp1 = Op.create("/bar",
            new byte[] { 1 }, null, CreateMode.PERSISTENT);
    private static final Op dummyCreateOp2 = Op.create("/baz",
            new byte[] { 2 }, null, CreateMode.PERSISTENT);
    private static final Op dummyCreateOp3 = Op.create("/fuzz",
            new byte[] { 2 }, null, CreateMode.PERSISTENT);
    private static final Op dummyDeleteOp0 = Op.delete("/foo", -1);;
    private static final Op dummyDeleteOp1 = Op.delete("/bar", -1);
    private static final Op dummyDeleteOp2 = Op.delete("/baz", -1);
    private static final Op dummyDeleteOp3 = Op.delete("/fuzz", -1);
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
    private static final String dummyId0 = UUID.randomUUID().toString();
    private static final String dummyId1 = UUID.randomUUID().toString();
    private static final String dummyId2 = UUID.randomUUID().toString();
    private static Set<String> dummyIds = null;
    static {
        dummyIds = new TreeSet<String>();
        dummyIds.add(dummyId0);
        dummyIds.add(dummyId1);
        dummyIds.add(dummyId2);
    }

    @Before
    public void setUp() throws Exception {
        this.opBuilderMock = mock(RouterOpBuilder.class);
        this.portOpServiceMock = mock(PortOpService.class);
        this.chainOpServiceMock = mock(ChainOpService.class);
        this.bridgeOpBuilderMock = mock(BridgeOpBuilder.class);
        this.zkDaoMock = mock(RouterZkDao.class);
        this.service = new RouterOpService(this.opBuilderMock,
                this.chainOpServiceMock, this.portOpServiceMock,
                this.bridgeOpBuilderMock, this.zkDaoMock);
    }

    @Test
    public void testBuildCreateRouterSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        RouterMgmtConfig mgmtConfig = new RouterMgmtConfig();
        mgmtConfig.tenantId = "foo";
        mgmtConfig.name = "bar";
        RouterNameMgmtConfig nameConfig = new RouterNameMgmtConfig();

        // Mock the path builder
        when(opBuilderMock.getRouterCreateOps(id)).thenReturn(dummyCreateOps);
        when(opBuilderMock.getRouterCreateOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp0);
        when(opBuilderMock.getRouterRoutersCreateOp(id)).thenReturn(
                dummyCreateOp1);
        when(opBuilderMock.getRouterBridgesCreateOp(id)).thenReturn(
                dummyCreateOp3);
        when(opBuilderMock.getRouterTablesCreateOp(id)).thenReturn(
                dummyCreateOp2);
        for (ChainTable chainTable : ChainTable.class.getEnumConstants()) {
            when(opBuilderMock.getRouterTableCreateOp(id, chainTable))
                    .thenReturn(dummyCreateOp0);
            when(opBuilderMock.getRouterTableChainsCreateOp(id, chainTable))
                    .thenReturn(dummyCreateOp1);
            when(opBuilderMock.getRouterTableChainNamesCreateOp(id, chainTable))
                    .thenReturn(dummyCreateOp2);
            when(chainOpServiceMock.buildBuiltInChains(id, chainTable))
                    .thenReturn(dummyCreateOps);
        }

        when(opBuilderMock.getTenantRouterCreateOp(mgmtConfig.tenantId, id))
                .thenReturn(dummyCreateOp0);
        when(opBuilderMock.getTenantRouterNameCreateOp(mgmtConfig.tenantId,
                mgmtConfig.name, nameConfig)).thenReturn(dummyCreateOp1);

        List<Op> ops = service.buildCreate(id, mgmtConfig, nameConfig);

        int chainNum = ChainTable.class.getEnumConstants().length;
        int chainOpNum = chainNum * 6;
        Assert.assertEquals(9 + chainOpNum, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.remove(0));
        Assert.assertEquals(dummyCreateOp1, ops.remove(0));
        Assert.assertEquals(dummyCreateOp2, ops.remove(0));
        Assert.assertEquals(dummyCreateOp0, ops.remove(0));
        Assert.assertEquals(dummyCreateOp1, ops.remove(0));
        Assert.assertEquals(dummyCreateOp3, ops.remove(0));
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
    }

    @Test
    public void testBuildDeleteWithCascadeSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        RouterMgmtConfig mgmtConfig = new RouterMgmtConfig();
        mgmtConfig.tenantId = "foo";
        mgmtConfig.name = "bar";

        // Mock the path builder
        when(opBuilderMock.getRouterDeleteOp(id)).thenReturn(dummyDeleteOp2);
        when(zkDaoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(opBuilderMock.getRouterDeleteOps(id)).thenReturn(dummyDeleteOps);
        when(portOpServiceMock.buildRouterPortsDelete(id)).thenReturn(
                dummyDeleteOps);
        when(opBuilderMock.getTenantRouterNameDeleteOp(mgmtConfig.tenantId,
                mgmtConfig.name)).thenReturn(dummyDeleteOp0);
        when(opBuilderMock.getTenantRouterDeleteOp(mgmtConfig.tenantId, id))
                .thenReturn(dummyDeleteOp1);

        for (ChainTable chainTable : ChainTable.class.getEnumConstants()) {
            when(chainOpServiceMock.buildDeleteRouterChains(id, chainTable))
                    .thenReturn(dummyDeleteOps);
            when(opBuilderMock.getRouterTableChainNamesDeleteOp(id, chainTable))
                    .thenReturn(dummyDeleteOp0);
            when(opBuilderMock.getRouterTableChainsDeleteOp(id, chainTable))
                    .thenReturn(dummyDeleteOp1);
            when(opBuilderMock.getRouterTableDeleteOp(id, chainTable))
                    .thenReturn(dummyDeleteOp2);
        }

        when(opBuilderMock.getRouterTablesDeleteOp(id)).thenReturn(
                dummyDeleteOp0);
        when(opBuilderMock.getRouterRoutersDeleteOp(id)).thenReturn(
                dummyDeleteOp1);
        when(opBuilderMock.getRouterBridgesDeleteOp(id)).thenReturn(
                dummyDeleteOp3);

        List<Op> ops = service.buildDelete(id, true);

        int chainNum = ChainTable.class.getEnumConstants().length;
        int chainOpNum = chainNum * 6;
        Assert.assertEquals(12 + chainOpNum, ops.size());
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
        Assert.assertEquals(dummyDeleteOp3, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp2, ops.remove(0));

    }

    @Test
    public void testBuildDeleteWithNoCascadeSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        RouterMgmtConfig mgmtConfig = new RouterMgmtConfig();
        mgmtConfig.tenantId = "foo";
        mgmtConfig.name = "bar";

        // Mock the path builder
        when(zkDaoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(portOpServiceMock.buildRouterPortsDelete(id)).thenReturn(
                dummyDeleteOps);
        when(
                opBuilderMock.getTenantRouterNameDeleteOp(mgmtConfig.tenantId,
                        mgmtConfig.name)).thenReturn(dummyDeleteOp0);
        when(opBuilderMock.getTenantRouterDeleteOp(mgmtConfig.tenantId, id))
                .thenReturn(dummyDeleteOp1);

        for (ChainTable chainTable : ChainTable.class.getEnumConstants()) {
            when(chainOpServiceMock.buildDeleteRouterChains(id, chainTable))
                    .thenReturn(dummyDeleteOps);
            when(opBuilderMock.getRouterTableChainNamesDeleteOp(id, chainTable))
                    .thenReturn(dummyDeleteOp0);
            when(opBuilderMock.getRouterTableChainsDeleteOp(id, chainTable))
                    .thenReturn(dummyDeleteOp1);
            when(opBuilderMock.getRouterTableDeleteOp(id, chainTable))
                    .thenReturn(dummyDeleteOp2);
        }

        when(opBuilderMock.getRouterTablesDeleteOp(id)).thenReturn(
                dummyDeleteOp0);
        when(opBuilderMock.getRouterRoutersDeleteOp(id)).thenReturn(
                dummyDeleteOp1);
        when(opBuilderMock.getRouterDeleteOp(id)).thenReturn(dummyDeleteOp2);
        when(opBuilderMock.getRouterBridgesDeleteOp(id)).thenReturn(
                dummyDeleteOp3);

        List<Op> ops = service.buildDelete(id, false);

        int chainNum = ChainTable.class.getEnumConstants().length;
        int chainOpNum = chainNum * 6;
        Assert.assertEquals(9 + chainOpNum, ops.size());
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
        Assert.assertEquals(dummyDeleteOp3, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp2, ops.remove(0));
    }

    @Test
    public void testBuildUpdateSuccess() throws Exception {
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
                opBuilderMock.getTenantRouterNameDeleteOp(mgmtConfig.tenantId,
                        mgmtConfig.name)).thenReturn(dummyCreateOp0);
        when(
                opBuilderMock.getTenantRouterNameCreateOp(mgmtConfig.tenantId,
                        name, nameConfig)).thenReturn(dummyCreateOp1);
        when(opBuilderMock.getRouterSetDataOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp2);

        List<Op> ops = service.buildUpdate(id, name);

        Assert.assertEquals(3, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
        Assert.assertEquals(dummyCreateOp1, ops.get(1));
        Assert.assertEquals(dummyCreateOp2, ops.get(2));
        Assert.assertEquals(name, mgmtConfig.name);
    }

    @Test
    public void testBuildLinkSuccess() throws Exception {
        UUID portId = UUID.randomUUID();
        PortConfig config = new LogicalRouterPortConfig();
        UUID peerPortId = UUID.randomUUID();
        PortConfig peerConfig = new LogicalRouterPortConfig();

        service.buildLink(portId, config, peerPortId, peerConfig);

        verify(portOpServiceMock, times(1)).buildCreateLink(portId, config,
                peerPortId, peerConfig);
    }

    @Test
    public void testBuildUnlinkSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        PeerRouterConfig config = new PeerRouterConfig();
        config.portId = UUID.randomUUID();
        config.peerPortId = UUID.randomUUID();

        when(zkDaoMock.getRouterLinkData(id, peerId)).thenReturn(config);

        service.buildUnlink(id, peerId);

        verify(portOpServiceMock, times(1)).buildDeleteLink(config.portId,
                config.peerPortId);
        verify(opBuilderMock, times(1)).getRouterRouterDeleteOp(id, peerId);
        verify(opBuilderMock, times(1)).getRouterRouterDeleteOp(peerId, id);
    }

    @Test
    public void testBuildTenantRoutersDeleteSuccess() throws Exception {
        RouterMgmtConfig mgmtConfig = new RouterMgmtConfig();
        mgmtConfig.tenantId = "foo";
        mgmtConfig.name = "bar";

        when(zkDaoMock.getIds(mgmtConfig.tenantId)).thenReturn(dummyIds);
        when(zkDaoMock.getMgmtData(any(UUID.class))).thenReturn(mgmtConfig);
        service.buildTenantRoutersDelete(mgmtConfig.tenantId);

        verify(opBuilderMock, times(1)).getRouterDeleteOps(
                UUID.fromString(dummyId0));
        verify(opBuilderMock, times(1)).getRouterDeleteOps(
                UUID.fromString(dummyId1));
        verify(opBuilderMock, times(1)).getRouterDeleteOps(
                UUID.fromString(dummyId2));
        verify(opBuilderMock, times(1)).getRouterDeleteOp(
                UUID.fromString(dummyId0));
        verify(opBuilderMock, times(1)).getRouterDeleteOp(
                UUID.fromString(dummyId1));
        verify(opBuilderMock, times(1)).getRouterDeleteOp(
                UUID.fromString(dummyId2));
    }
}
