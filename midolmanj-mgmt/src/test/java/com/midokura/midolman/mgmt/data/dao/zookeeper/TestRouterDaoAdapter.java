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
import com.midokura.midolman.mgmt.data.dao.RouteDao;
import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.mgmt.data.zookeeper.op.RouterOpService;

@RunWith(MockitoJUnitRunner.class)
public class TestRouterDaoAdapter {

    private RouterDaoAdapter testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private RouterZkDao dao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private RouterOpService opService;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private PortDao portDao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private RouteDao routeDao;

    @Before
    public void setUp() throws Exception {
        testObject = new RouterDaoAdapter(dao, opService, portDao, routeDao);
    }

    @Test
    public void testGetNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        doReturn(false).when(dao).exists(id);

        Router router = testObject.get(id);

        Assert.assertNull(router);
    }
}
