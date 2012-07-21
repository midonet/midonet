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

import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.data.zookeeper.dao.RouteDaoImpl;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.RouteZkManager;

@RunWith(MockitoJUnitRunner.class)
public class TestRouteDaoImpl {

    private RouteDaoImpl testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private RouteZkManager dao;

    @Before
    public void setUp() throws Exception {
        testObject = new RouteDaoImpl(dao);
    }

    @Test
    public void testGetNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(NoStatePathException.class).when(dao).get(id);

        Route route = testObject.get(id);

        Assert.assertNull(route);
    }
}
