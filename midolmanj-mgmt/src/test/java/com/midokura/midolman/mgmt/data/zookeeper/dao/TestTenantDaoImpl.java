/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.ZkManager;

@RunWith(MockitoJUnitRunner.class)
public class TestTenantDaoImpl {

    private TenantDaoImpl testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private ZkManager dao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private PathBuilder pathBuilder;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private RouterZkDao routerDao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private BridgeZkDao bridgeDao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private ChainZkDao chainDao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private PortGroupZkDao portGroupDao;

    @Before
    public void setUp() throws Exception {
        testObject = new TenantDaoImpl(dao, pathBuilder, bridgeDao, routerDao,
                chainDao, portGroupDao);
    }

    @Test
    public void testGetNotExist() throws Exception {
        doReturn(false).when(dao).exists(any(String.class));

        Tenant tenant = testObject.get("foo");

        Assert.assertNull(tenant);
    }
}
