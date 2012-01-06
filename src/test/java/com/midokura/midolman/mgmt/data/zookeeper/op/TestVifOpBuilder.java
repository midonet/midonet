/*
 * @(#)TestVifOpBuilder        1.6 12/1/6
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

import com.midokura.midolman.mgmt.data.dto.config.VifConfig;

public class TestVifOpBuilder {

    private VifOpPathBuilder pathBuilderMock = null;
    private PortOpBuilder portOpBuilderMock = null;
    private VifOpBuilder builder = null;
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
        this.pathBuilderMock = mock(VifOpPathBuilder.class);
        this.portOpBuilderMock = mock(PortOpBuilder.class);
        this.builder = new VifOpBuilder(this.pathBuilderMock,
                this.portOpBuilderMock);
    }

    @Test
    public void TestBuildCreatePortSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        VifConfig config = new VifConfig();
        config.portId = UUID.randomUUID();

        // Mock the path builder
        when(pathBuilderMock.getVifCreateOp(id, config)).thenReturn(
                dummyCreateOp0);
        when(portOpBuilderMock.buildPlug(config.portId, id)).thenReturn(
                dummyCreateOps);

        List<Op> ops = builder.buildCreate(id, config);

        Assert.assertEquals(4, ops.size());
        Assert.assertEquals(dummyCreateOp0, ops.get(0));
        Assert.assertEquals(dummyCreateOp0, ops.get(1));
        Assert.assertEquals(dummyCreateOp1, ops.get(2));
        Assert.assertEquals(dummyCreateOp2, ops.get(3));
    }

    @Test
    public void TestBuildDeleteSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID portId = UUID.randomUUID();

        // Mock the path builder
        when(portOpBuilderMock.buildPlug(portId, null)).thenReturn(
                dummyDeleteOps);
        when(pathBuilderMock.getVifDeleteOp(id)).thenReturn(dummyDeleteOp0);

        List<Op> ops = builder.buildDelete(id, portId);

        Assert.assertEquals(4, ops.size());
        Assert.assertEquals(dummyDeleteOp0, ops.get(0));
        Assert.assertEquals(dummyDeleteOp1, ops.get(1));
        Assert.assertEquals(dummyDeleteOp2, ops.get(2));
        Assert.assertEquals(dummyDeleteOp0, ops.get(3));
    }

}
