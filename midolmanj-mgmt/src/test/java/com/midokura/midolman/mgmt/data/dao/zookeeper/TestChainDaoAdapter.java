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

import com.midokura.midolman.mgmt.data.dao.RuleDao;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.data.zookeeper.op.ChainOpService;

@RunWith(MockitoJUnitRunner.class)
public class TestChainDaoAdapter {

    private ChainDaoAdapter testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private ChainZkDao dao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private ChainOpService opService;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private RuleDao ruleDao;

    @Before
    public void setUp() throws Exception {
        testObject = new ChainDaoAdapter(dao, opService, ruleDao);
    }

    @Test
    public void testGetNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(false).when(dao).exists(id);

        Chain chain = testObject.get(id);

        Assert.assertNull(chain);
    }
}
