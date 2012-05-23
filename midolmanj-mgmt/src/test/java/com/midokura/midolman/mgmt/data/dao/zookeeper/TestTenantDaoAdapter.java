/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import static org.mockito.Mockito.doThrow;
import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dao.PortGroupDao;
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.mgmt.data.zookeeper.op.TenantOpService;
import com.midokura.midolman.state.NoStatePathException;

@RunWith(MockitoJUnitRunner.class)
public class TestTenantDaoAdapter {

    private TenantDaoAdapter testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private TenantZkDao dao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private TenantOpService opService;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private RouterDao routerDao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private BridgeDao bridgeDao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private ChainDao chainDao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private PortGroupDao portGroupDao;

    @Before
    public void setUp() throws Exception {
        testObject = new TenantDaoAdapter(dao, opService, bridgeDao, routerDao,
                chainDao, portGroupDao);
    }

    @Test
    public void testGetNotExist() throws Exception {
        String id = "foo";
        doThrow(NoStatePathException.class).when(dao).getData(id);

        Tenant tenant = testObject.get(id);

        Assert.assertNull(tenant);
    }
}
