/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import junit.framework.Assert;

import org.apache.zookeeper.Op;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.midokura.midolman.mgmt.data.dao.zookeeper.ChainZkDao;

@RunWith(MockitoJUnitRunner.class)
public class TestChainOpService {

    private ChainOpService testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private ChainOpBuilder opBuilder;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private ChainZkDao zkDao;

    @Before
    public void setUp() {
        testObject = new ChainOpService(opBuilder, zkDao);
    }

    @Test
    public void testBuildDeleteWithCascadeSuccess() throws Exception {

        // Setup
        UUID id = UUID.randomUUID();
        InOrder inOrder = inOrder(opBuilder);

        // Execute
        List<Op> ops = testObject.buildDelete(id, true);

        // Verify the order of execution
        Assert.assertTrue(ops.size() > 0);
        inOrder.verify(opBuilder).getChainDeleteOps(id);
        inOrder.verify(opBuilder).getChainDeleteOp(id);
    }

    @Test
    public void testBuildDeleteWithNoCascadeSuccess() throws Exception {

        // Setup
        UUID id = UUID.randomUUID();

        // Execute
        List<Op> ops = testObject.buildDelete(id, false);

        // Verify that cascade did not happen
        Assert.assertTrue(ops.size() > 0);
        verify(opBuilder, never()).getChainDeleteOps(id);
    }

}
