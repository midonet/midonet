/*
 * Copyright 2012 Midokura KK
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

import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.data.zookeeper.op.BridgeOpService;

@RunWith(MockitoJUnitRunner.class)
public class TestBridgeDaoAdapter {

    private BridgeDaoAdapter testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private BridgeZkDao dao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private BridgeOpService opService;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private PortDao portDao;

    @Before
    public void setUp() throws Exception {
        testObject = new BridgeDaoAdapter(dao, opService, portDao);
    }

    @Test
    public void testGetNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(false).when(dao).exists(id);

        Bridge bridge = testObject.get(id);

        Assert.assertNull(bridge);
    }
}
