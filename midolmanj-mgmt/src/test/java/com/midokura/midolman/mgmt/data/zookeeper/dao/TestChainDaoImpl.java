/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import static org.mockito.Mockito.doReturn;

import java.util.UUID;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.midokura.midolman.mgmt.data.dao.RuleDao;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.zookeeper.dao.ChainZkDaoImpl;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.ZkConfigSerializer;

@RunWith(MockitoJUnitRunner.class)
public class TestChainDaoImpl {

    private ChainZkDaoImpl testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private ChainZkManager dao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private PathBuilder pathBuilder;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private ZkConfigSerializer serializer;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private RuleDao ruleDao;

    @Before
    public void setUp() throws Exception {
        testObject = new ChainZkDaoImpl(dao, pathBuilder, serializer, ruleDao);
    }

    @Test
    public void testGetNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(false).when(dao).exists(id);

        Chain chain = testObject.get(id);

        Assert.assertNull(chain);
    }
}
