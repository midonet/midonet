/*
 * @(#)TestTenantOpService        1.6 12/1/6
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

public class TestTenantOpService {

    private TenantOpBuilder opBuilderMock = null;
    private TenantZkDao zkDaoMock = null;
    private RouterOpService routerOpServiceMock = null;
    private BridgeOpService bridgeOpServiceMock = null;
    private TenantOpService service = null;
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
        this.opBuilderMock = mock(TenantOpBuilder.class);
        this.routerOpServiceMock = mock(RouterOpService.class);
        this.bridgeOpServiceMock = mock(BridgeOpService.class);
        this.zkDaoMock = mock(TenantZkDao.class);
        this.service = new TenantOpService(this.opBuilderMock,
                this.bridgeOpServiceMock, this.routerOpServiceMock,
                this.zkDaoMock);
    }

    @Test
    public void testBuildCreateRouterSuccess() throws Exception {
        String id = "foo";

        // Mock the path builder
        when(opBuilderMock.getTenantCreateOp(id)).thenReturn(dummyCreateOp0);
        when(opBuilderMock.getTenantBridgesCreateOp(id)).thenReturn(
                dummyCreateOp1);
        when(opBuilderMock.getTenantRoutersCreateOp(id)).thenReturn(
                dummyCreateOp2);
        when(opBuilderMock.getTenantBridgeNamesCreateOp(id)).thenReturn(
                dummyCreateOp0);
        when(opBuilderMock.getTenantRouterNamesCreateOp(id)).thenReturn(
                dummyCreateOp1);

        List<Op> ops = service.buildCreate(id);

        Assert.assertEquals(5, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.remove(0));
        Assert.assertEquals(dummyCreateOp1, ops.remove(0));
        Assert.assertEquals(dummyCreateOp2, ops.remove(0));
        Assert.assertEquals(dummyCreateOp0, ops.remove(0));
        Assert.assertEquals(dummyCreateOp1, ops.remove(0));
    }

    @Test
    public void testBuildDeleteSuccess() throws Exception {
        String id = "foo";

        // Mock the path builder
        when(zkDaoMock.exists(id)).thenReturn(true);
        when(routerOpServiceMock.buildTenantRoutersDelete(id)).thenReturn(
                dummyDeleteOps);
        when(bridgeOpServiceMock.buildTenantBridgesDelete(id)).thenReturn(
                dummyDeleteOps);

        when(opBuilderMock.getTenantRouterNamesDeleteOp(id)).thenReturn(
                dummyDeleteOp0);
        when(opBuilderMock.getTenantBridgeNamesDeleteOp(id)).thenReturn(
                dummyDeleteOp1);
        when(opBuilderMock.getTenantRoutersDeleteOp(id)).thenReturn(
                dummyDeleteOp2);
        when(opBuilderMock.getTenantBridgesDeleteOp(id)).thenReturn(
                dummyDeleteOp0);
        when(opBuilderMock.getTenantDeleteOp(id)).thenReturn(dummyDeleteOp1);

        List<Op> ops = service.buildDelete(id);

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
    public void testBuildDeleteDoesNotExist() throws Exception {
        String id = "foo";
        when(zkDaoMock.exists(id)).thenReturn(false);
        service.buildDelete(id);
    }
}