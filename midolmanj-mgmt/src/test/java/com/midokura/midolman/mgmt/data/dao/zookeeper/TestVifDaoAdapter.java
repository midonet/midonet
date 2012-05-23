/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import static org.mockito.Mockito.doReturn;

import java.util.UUID;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.midokura.midolman.mgmt.data.dto.Vif;
import com.midokura.midolman.mgmt.data.zookeeper.op.VifOpService;

@RunWith(MockitoJUnitRunner.class)
public class TestVifDaoAdapter {

    private VifDaoAdapter testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private VifZkDao dao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private VifOpService opService;

    @Before
    public void setUp() throws Exception {
        testObject = new VifDaoAdapter(dao, opService);
    }

    @Test
    public void testGetNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(false).when(dao).exists(id);

        Vif vif = testObject.get(id);

        Assert.assertNull(vif);
    }
}
