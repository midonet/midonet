/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import static org.mockito.Mockito.doReturn;

import java.util.UUID;

import com.midokura.midolman.state.zkManagers.BridgeZkManager;
import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.state.PathBuilder;
import com.midokura.midolman.state.ZkConfigSerializer;

@RunWith(MockitoJUnitRunner.class)
public class TestBridgeZkDaoImpl {

    private BridgeZkDaoImpl testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private BridgeZkManager dao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private PathBuilder pathBuilder;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private ZkConfigSerializer serializer;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private PortDao portDao;

    @Before
    public void setUp() throws Exception {
        testObject = new BridgeZkDaoImpl(dao, pathBuilder, serializer, portDao);
    }

    @Test
    public void testGetNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(false).when(dao).exists(id);

        Bridge bridge = testObject.get(id);

        Assert.assertNull(bridge);
    }
}
