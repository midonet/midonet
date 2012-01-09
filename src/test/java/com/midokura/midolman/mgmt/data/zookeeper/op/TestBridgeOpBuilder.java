/*
 * @(#)TestBridgeOpBuilder        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
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

public class TestBridgeOpBuilder {

    private BridgeOpPathBuilder pathBuilderMock = null;
    private BridgeZkDao zkDaoMock = null;
    private PortOpBuilder portOpBuilderMock = null;
    private BridgeOpBuilder builder = null;
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
        this.pathBuilderMock = mock(BridgeOpPathBuilder.class);
        this.portOpBuilderMock = mock(PortOpBuilder.class);
        this.zkDaoMock = mock(BridgeZkDao.class);
        this.builder = new BridgeOpBuilder(this.pathBuilderMock,
                this.portOpBuilderMock, this.zkDaoMock);
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
        when(pathBuilderMock.getBridgeCreateOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp0);
        when(pathBuilderMock.getTenantBridgeCreateOp(mgmtConfig.tenantId, id))
                .thenReturn(dummyCreateOp1);
        when(
                pathBuilderMock.getTenantBridgeNameCreateOp(
                        mgmtConfig.tenantId, mgmtConfig.name, nameConfig))
                .thenReturn(dummyCreateOp2);
        when(pathBuilderMock.getBridgeCreateOps(id, config)).thenReturn(
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
        BridgeMgmtConfig mgmtConfig = new BridgeMgmtConfig();
        mgmtConfig.tenantId = "foo";
        mgmtConfig.name = "bar";

        // Mock the path builder
        when(zkDaoMock.getMgmtData(id)).thenReturn(mgmtConfig);
        when(pathBuilderMock.getBridgeDeleteOps(id)).thenReturn(dummyDeleteOps);
        when(portOpBuilderMock.buildBridgePortsDelete(id)).thenReturn(
                dummyDeleteOps);
        when(
                pathBuilderMock.getTenantBridgeNameDeleteOp(
                        mgmtConfig.tenantId, mgmtConfig.name)).thenReturn(
                dummyDeleteOp0);
        when(pathBuilderMock.getTenantBridgeDeleteOp(mgmtConfig.tenantId, id))
                .thenReturn(dummyDeleteOp1);
        when(pathBuilderMock.getBridgeDeleteOp(id)).thenReturn(dummyDeleteOp2);

        List<Op> ops = builder.buildDelete(id, true);

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
        when(portOpBuilderMock.buildBridgePortsDelete(id)).thenReturn(
                dummyDeleteOps);
        when(
                pathBuilderMock.getTenantBridgeNameDeleteOp(
                        mgmtConfig.tenantId, mgmtConfig.name)).thenReturn(
                dummyDeleteOp0);
        when(pathBuilderMock.getTenantBridgeDeleteOp(mgmtConfig.tenantId, id))
                .thenReturn(dummyDeleteOp1);
        when(pathBuilderMock.getBridgeDeleteOp(id)).thenReturn(dummyDeleteOp2);

        List<Op> ops = builder.buildDelete(id, false);

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
                pathBuilderMock.getTenantBridgeNameDeleteOp(
                        mgmtConfig.tenantId, mgmtConfig.name)).thenReturn(
                dummyCreateOp0);
        when(
                pathBuilderMock.getTenantBridgeNameCreateOp(
                        mgmtConfig.tenantId, name, nameConfig)).thenReturn(
                dummyCreateOp1);
        when(pathBuilderMock.getBridgeSetDataOp(id, mgmtConfig)).thenReturn(
                dummyCreateOp2);

        List<Op> ops = builder.buildUpdate(id, name);

        Assert.assertEquals(3, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
        Assert.assertEquals(dummyCreateOp1, ops.get(1));
        Assert.assertEquals(dummyCreateOp2, ops.get(2));
        Assert.assertEquals(name, mgmtConfig.name);
    }
}
