/*
 * @(#)TestBridgeOpService        1.6 12/1/6
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

import com.midokura.midolman.mgmt.data.dao.zookeeper.BridgeZkDao;
import com.midokura.midolman.mgmt.data.dto.config.BridgeMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.BridgeNameMgmtConfig;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;

public class TestBridgeOpService {

    private BridgeOpBuilder opBuilderMock = null;
    private BridgeZkDao zkDaoMock = null;
    private PortOpService portOpServiceMock = null;
    private BridgeOpService service = null;
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
        this.opBuilderMock = mock(BridgeOpBuilder.class);
        this.portOpServiceMock = mock(PortOpService.class);
        this.zkDaoMock = mock(BridgeZkDao.class);
        this.service = new BridgeOpService(this.opBuilderMock,
                this.portOpServiceMock, this.zkDaoMock);
    }

    @Test
    public void TestBuildCreateBridgeSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        BridgeConfig config = new BridgeConfig();
        BridgeMgmtConfig mgmtConfig = new BridgeMgmtConfig();
        mgmtConfig.tenantId = "foo";
        mgmtConfig.name = "bar";
        BridgeNameMgmtConfig nameConfig = new BridgeNameMgmtConfig();

        // Mock the path builder
        when(opBuilderMock.getBridgeCreateOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp0);
        when(opBuilderMock.getTenantBridgeCreateOp(mgmtConfig.tenantId, id))
                .thenReturn(dummyCreateOp1);
        when(
                opBuilderMock.getTenantBridgeNameCreateOp(mgmtConfig.tenantId,
                        mgmtConfig.name, nameConfig))
                .thenReturn(dummyCreateOp2);
        when(opBuilderMock.getBridgeCreateOps(id, config)).thenReturn(
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
    public void TestBuildDeleteWithCascadeSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        BridgeMgmtConfig mgmtConfig = new BridgeMgmtConfig();
        mgmtConfig.tenantId = "foo";
        mgmtConfig.name = "bar";

        // Mock the path builder
        when(zkDaoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(opBuilderMock.getBridgeDeleteOps(id)).thenReturn(dummyDeleteOps);
        when(portOpServiceMock.buildBridgePortsDelete(id)).thenReturn(
                dummyDeleteOps);
        when(
                opBuilderMock.getTenantBridgeNameDeleteOp(mgmtConfig.tenantId,
                        mgmtConfig.name)).thenReturn(dummyDeleteOp0);
        when(opBuilderMock.getTenantBridgeDeleteOp(mgmtConfig.tenantId, id))
                .thenReturn(dummyDeleteOp1);
        when(opBuilderMock.getBridgeDeleteOp(id)).thenReturn(dummyDeleteOp2);

        List<Op> ops = service.buildDelete(id, true);

        Assert.assertEquals(9, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.get(0));
        Assert.assertEquals(dummyDeleteOp1, ops.get(1));
        Assert.assertEquals(dummyDeleteOp2, ops.get(2));
        Assert.assertEquals(dummyDeleteOp0, ops.get(3));
        Assert.assertEquals(dummyDeleteOp1, ops.get(4));
        Assert.assertEquals(dummyDeleteOp2, ops.get(5));
        Assert.assertEquals(dummyDeleteOp0, ops.get(6));
        Assert.assertEquals(dummyDeleteOp1, ops.get(7));
        Assert.assertEquals(dummyDeleteOp2, ops.get(8));
    }

    @Test
    public void TestBuildDeleteWithNoCascadeSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        BridgeMgmtConfig mgmtConfig = new BridgeMgmtConfig();
        mgmtConfig.tenantId = "foo";
        mgmtConfig.name = "bar";

        // Mock the path builder
        when(zkDaoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(portOpServiceMock.buildBridgePortsDelete(id)).thenReturn(
                dummyDeleteOps);
        when(
                opBuilderMock.getTenantBridgeNameDeleteOp(mgmtConfig.tenantId,
                        mgmtConfig.name)).thenReturn(dummyDeleteOp0);
        when(opBuilderMock.getTenantBridgeDeleteOp(mgmtConfig.tenantId, id))
                .thenReturn(dummyDeleteOp1);
        when(opBuilderMock.getBridgeDeleteOp(id)).thenReturn(dummyDeleteOp2);

        List<Op> ops = service.buildDelete(id, false);

        Assert.assertEquals(6, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.get(0));
        Assert.assertEquals(dummyDeleteOp1, ops.get(1));
        Assert.assertEquals(dummyDeleteOp2, ops.get(2));
        Assert.assertEquals(dummyDeleteOp0, ops.get(3));
        Assert.assertEquals(dummyDeleteOp1, ops.get(4));
        Assert.assertEquals(dummyDeleteOp2, ops.get(5));
    }

    @Test
    public void TestBuildUpdateSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        String name = "foo";
        BridgeMgmtConfig mgmtConfig = new BridgeMgmtConfig();
        mgmtConfig.tenantId = "bar";
        mgmtConfig.name = "baz";
        BridgeNameMgmtConfig nameConfig = new BridgeNameMgmtConfig();

        // Mock the path builder
        when(zkDaoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(zkDaoMock.getNameData(mgmtConfig.tenantId, mgmtConfig.name))
                .thenReturn(nameConfig);
        when(
                opBuilderMock.getTenantBridgeNameDeleteOp(mgmtConfig.tenantId,
                        mgmtConfig.name)).thenReturn(dummyCreateOp0);
        when(
                opBuilderMock.getTenantBridgeNameCreateOp(mgmtConfig.tenantId,
                        name, nameConfig)).thenReturn(dummyCreateOp1);
        when(opBuilderMock.getBridgeSetDataOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp2);

        List<Op> ops = service.buildUpdate(id, name);

        Assert.assertEquals(3, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
        Assert.assertEquals(dummyCreateOp1, ops.get(1));
        Assert.assertEquals(dummyCreateOp2, ops.get(2));
        Assert.assertEquals(name, mgmtConfig.name);
    }

    @Test
    public void TestBuildTenantRoutersDeleteSuccess() throws Exception {
        BridgeMgmtConfig mgmtConfig = new BridgeMgmtConfig();
        mgmtConfig.tenantId = "foo";
        mgmtConfig.name = "bar";

        when(zkDaoMock.getIds(mgmtConfig.tenantId)).thenReturn(dummyIds);
        when(zkDaoMock.getMgmtData(any(UUID.class))).thenReturn(mgmtConfig);
        service.buildTenantBridgesDelete(mgmtConfig.tenantId);

        verify(opBuilderMock, times(1)).getBridgeDeleteOps(
                UUID.fromString(dummyId0));
        verify(opBuilderMock, times(1)).getBridgeDeleteOps(
                UUID.fromString(dummyId1));
        verify(opBuilderMock, times(1)).getBridgeDeleteOps(
                UUID.fromString(dummyId2));
        verify(opBuilderMock, times(1)).getBridgeDeleteOp(
                UUID.fromString(dummyId0));
        verify(opBuilderMock, times(1)).getBridgeDeleteOp(
                UUID.fromString(dummyId1));
        verify(opBuilderMock, times(1)).getBridgeDeleteOp(
                UUID.fromString(dummyId2));
    }
}
