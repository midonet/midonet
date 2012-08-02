/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import static org.mockito.Mockito.doThrow;

import java.util.UUID;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.zkManagers.RuleZkManager;

@RunWith(MockitoJUnitRunner.class)
public class TestRuleDaoImpl {

    private RuleDaoImpl testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private RuleZkManager dao;

    @Before
    public void setUp() throws Exception {
        testObject = new RuleDaoImpl(dao);
    }

    @Test
    public void testGetNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(NoStatePathException.class).when(dao).get(id);

        Rule rule = testObject.get(id);

        Assert.assertNull(rule);
    }
}
