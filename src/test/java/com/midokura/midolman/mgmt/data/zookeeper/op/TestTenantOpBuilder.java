/*
 * @(#)TestTenantOpBuilder        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import junit.framework.Assert;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.mgmt.data.dao.zookeeper.TenantZkDao;
import com.midokura.midolman.state.NoStatePathException;

public class TestTenantOpBuilder {

    private TenantOpPathBuilder pathBuilderMock = null;
    private TenantZkDao zkDaoMock = null;
    private RouterOpBuilder routerOpBuilderMock = null;
    private BridgeOpBuilder bridgeOpBuilderMock = null;
    private TenantOpBuilder builder = null;
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
        this.pathBuilderMock = mock(TenantOpPathBuilder.class);
        this.routerOpBuilderMock = mock(RouterOpBuilder.class);
        this.bridgeOpBuilderMock = mock(BridgeOpBuilder.class);
        this.zkDaoMock = mock(TenantZkDao.class);
        this.builder = new TenantOpBuilder(this.pathBuilderMock,
                this.bridgeOpBuilderMock, this.routerOpBuilderMock,
                this.zkDaoMock);
    }

    @Test
    public void TestBuildCreateRouterSuccess() throws Exception {
        String id = "foo";

        // Mock the path builder
        when(pathBuilderMock.getTenantCreateOp(id)).thenReturn(dummyCreateOp0);
        when(pathBuilderMock.getTenantBridgesCreateOp(id)).thenReturn(
                dummyCreateOp1);
        when(pathBuilderMock.getTenantRoutersCreateOp(id)).thenReturn(
                dummyCreateOp2);
        when(pathBuilderMock.getTenantBridgeNamesCreateOp(id)).thenReturn(
                dummyCreateOp0);
        when(pathBuilderMock.getTenantRouterNamesCreateOp(id)).thenReturn(
                dummyCreateOp1);

        List<Op> ops = builder.buildCreate(id);

        Assert.assertEquals(5, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.remove(0));
        Assert.assertEquals(dummyCreateOp1, ops.remove(0));
        Assert.assertEquals(dummyCreateOp2, ops.remove(0));
        Assert.assertEquals(dummyCreateOp0, ops.remove(0));
        Assert.assertEquals(dummyCreateOp1, ops.remove(0));
    }

    @Test
    public void TestBuildDeleteSuccess() throws Exception {
        String id = "foo";

        // Mock the path builder
        when(zkDaoMock.exists(id)).thenReturn(true);
        when(routerOpBuilderMock.buildTenantRoutersDelete(id)).thenReturn(
                dummyDeleteOps);
        when(bridgeOpBuilderMock.buildTenantBridgesDelete(id)).thenReturn(
                dummyDeleteOps);

        when(pathBuilderMock.getTenantRouterNamesDeleteOp(id)).thenReturn(
                dummyDeleteOp0);
        when(pathBuilderMock.getTenantBridgeNamesDeleteOp(id)).thenReturn(
                dummyDeleteOp1);
        when(pathBuilderMock.getTenantRoutersDeleteOp(id)).thenReturn(
                dummyDeleteOp2);
        when(pathBuilderMock.getTenantBridgesDeleteOp(id)).thenReturn(
                dummyDeleteOp0);
        when(pathBuilderMock.getTenantDeleteOp(id)).thenReturn(dummyDeleteOp1);

        List<Op> ops = builder.buildDelete(id);

        Assert.assertEquals(11, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp1, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp2, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp0, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp1, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp2, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp0, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp1, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp2, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp0, ops.remove(0));
        Assert.assertEquals(dummyDeleteOp1, ops.remove(0));
    }

    @Test(expected = NoStatePathException.class)
    public void TestBuildDeleteDoesNotExist() throws Exception {
        String id = "foo";
        when(zkDaoMock.exists(id)).thenReturn(false);
        builder.buildDelete(id);
    }
}