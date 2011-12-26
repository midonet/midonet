/*
 * @(#)TestChainOpBuilder        1.6 11/12/26
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import junit.framework.Assert;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.junit.Before;
import org.junit.Test;

import static org.mockito.Mockito.*;

import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.dto.config.ChainMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.ChainNameMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.ChainTable;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;

public class TestChainOpBuilder {

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

    @Before
    public void setUp() throws Exception {
        this.pathBuilderMock = mock(ChainOpPathBuilder.class);
        this.builder = new ChainOpBuilder(this.pathBuilderMock);
    }

    @Test
    public void TestBuildCreateSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID routerId = UUID.randomUUID();
        String name = "foo";

        // Mock the path builder
        when(
                pathBuilderMock.getChainCreateOp(any(UUID.class),
                        any(ChainMgmtConfig.class))).thenReturn(dummyCreateOp0);
        when(
                pathBuilderMock.getRouterTableChainCreateOp(routerId,
                        ChainTable.NAT, id)).thenReturn(dummyCreateOp1);
        when(
                pathBuilderMock.getRouterTableChainNameCreateOp(
                        any(UUID.class), any(ChainTable.class), anyString(),
                        any(ChainNameMgmtConfig.class))).thenReturn(
                dummyCreateOp2);
        when(
                pathBuilderMock.getChainCreateOps(any(UUID.class),
                        any(ChainConfig.class))).thenReturn(dummyCreateOps);

        Chain chain = new Chain(id, routerId, ChainTable.NAT, name);
        List<Op> ops = builder.buildCreate(chain);

        Assert.assertEquals(6, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
        Assert.assertEquals(dummyCreateOp1, ops.get(1));
        Assert.assertEquals(dummyCreateOp2, ops.get(2));
        Assert.assertEquals(dummyCreateOp0, ops.get(3));
        Assert.assertEquals(dummyCreateOp1, ops.get(4));
        Assert.assertEquals(dummyCreateOp2, ops.get(5));
    }

    @Test(expected = IllegalArgumentException.class)
    public void TestBuildCreateNullInput() throws Exception {
        builder.buildCreate((Chain) null);
    }

    @Test
    public void TestBuildCreateWithListSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID routerId = UUID.randomUUID();
        String name = "foo";
        Chain chain = new Chain(id, routerId, ChainTable.NAT, name);

        builder = spy(new ChainOpBuilder(this.pathBuilderMock));
        doReturn(dummyCreateOps).when(builder).buildCreate(chain);

        List<Chain> chains = new ArrayList<Chain>();
        chains.add(chain);
        chains.add(chain);
        List<Op> ops = builder.buildCreate(chains);
        Assert.assertEquals(6, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
        Assert.assertEquals(dummyCreateOp1, ops.get(1));
        Assert.assertEquals(dummyCreateOp2, ops.get(2));
        Assert.assertEquals(dummyCreateOp0, ops.get(3));
        Assert.assertEquals(dummyCreateOp1, ops.get(4));
        Assert.assertEquals(dummyCreateOp2, ops.get(5));
    }

    @Test(expected = IllegalArgumentException.class)
    public void TestBuildCreateWithListNullInput() throws Exception {
        builder.buildCreate((List<Chain>) null);
    }

    @Test
    public void TestBuildDeleteWithCascadeSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID routerId = UUID.randomUUID();
        String name = "foo";

        // Mock the path builder
        when(pathBuilderMock.getChainDeleteOps(id)).thenReturn(dummyDeleteOps);
        when(
                pathBuilderMock.getRouterTableChainNameDeleteOp(routerId,
                        ChainTable.NAT, name)).thenReturn(dummyDeleteOp0);
        when(
                pathBuilderMock.getRouterTableChainDeleteOp(routerId,
                        ChainTable.NAT, id)).thenReturn(dummyDeleteOp1);
        when(pathBuilderMock.getChainDeleteOp(id)).thenReturn(dummyDeleteOp2);

        Chain chain = new Chain(id, routerId, ChainTable.NAT, name);
        List<Op> ops = builder.buildDelete(chain, true);
        verify(pathBuilderMock, times(1)).getChainDeleteOps(id);
        Assert.assertEquals(6, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.get(0));
        Assert.assertEquals(dummyDeleteOp1, ops.get(1));
        Assert.assertEquals(dummyDeleteOp2, ops.get(2));
        Assert.assertEquals(dummyDeleteOp0, ops.get(3));
        Assert.assertEquals(dummyDeleteOp1, ops.get(4));
        Assert.assertEquals(dummyDeleteOp2, ops.get(5));
    }

    @Test(expected = IllegalArgumentException.class)
    public void TestBuildDeleteWithNullInput() throws Exception {
        builder.buildDelete((Chain) null, true);
    }

    @Test
    public void TestBuildDeleteWithNoCascadeSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID routerId = UUID.randomUUID();
        String name = "foo";

        // Mock the path builder
        when(
                pathBuilderMock.getRouterTableChainNameDeleteOp(routerId,
                        ChainTable.NAT, name)).thenReturn(dummyDeleteOp0);
        when(
                pathBuilderMock.getRouterTableChainDeleteOp(routerId,
                        ChainTable.NAT, id)).thenReturn(dummyDeleteOp1);
        when(pathBuilderMock.getChainDeleteOp(id)).thenReturn(dummyDeleteOp2);

        Chain chain = new Chain(id, routerId, ChainTable.NAT, name);
        List<Op> ops = builder.buildDelete(chain, false);
        verify(pathBuilderMock, never()).getChainDeleteOps(id);
        Assert.assertEquals(3, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.get(0));
        Assert.assertEquals(dummyDeleteOp1, ops.get(1));
        Assert.assertEquals(dummyDeleteOp2, ops.get(2));
    }

    @Test
    public void TestBuildDeleteWithListSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID routerId = UUID.randomUUID();
        String name = "foo";
        Chain chain = new Chain(id, routerId, ChainTable.NAT, name);

        builder = spy(new ChainOpBuilder(this.pathBuilderMock));
        doReturn(dummyDeleteOps).when(builder).buildDelete(chain, true);

        List<Chain> chains = new ArrayList<Chain>();
        chains.add(chain);
        chains.add(chain);
        List<Op> ops = builder.buildDelete(chains, true);
        Assert.assertEquals(6, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.get(0));
        Assert.assertEquals(dummyDeleteOp1, ops.get(1));
        Assert.assertEquals(dummyDeleteOp2, ops.get(2));
        Assert.assertEquals(dummyDeleteOp0, ops.get(3));
        Assert.assertEquals(dummyDeleteOp1, ops.get(4));
        Assert.assertEquals(dummyDeleteOp2, ops.get(5));
    }

    @Test(expected = IllegalArgumentException.class)
    public void TestBuildDeleteWithListNullInput() throws Exception {
        builder.buildDelete((List<Chain>) null, true);
    }

}
