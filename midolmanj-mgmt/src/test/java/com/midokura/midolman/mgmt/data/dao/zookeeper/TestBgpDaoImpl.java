/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import static org.mockito.Mockito.doThrow;

import java.util.UUID;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.midokura.midolman.mgmt.data.dao.AdRouteDao;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.state.BgpZkManager;
import com.midokura.midolman.state.NoStatePathException;

@RunWith(MockitoJUnitRunner.class)
public class TestBgpDaoImpl {

    private BgpDaoImpl testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private AdRouteDao adRouteDao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private BgpZkManager dao;

    @Before
    public void setUp() throws Exception {
        testObject = new BgpDaoImpl(dao, adRouteDao);
    }

    @Test
    public void testGetNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(NoStatePathException.class).when(dao).get(id);

        Bgp bgp = testObject.get(id);

        Assert.assertNull(bgp);
    }
}
