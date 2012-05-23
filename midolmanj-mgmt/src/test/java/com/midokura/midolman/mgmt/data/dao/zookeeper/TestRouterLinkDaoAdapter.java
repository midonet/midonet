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

import com.midokura.midolman.mgmt.data.dto.PeerRouterLink;
import com.midokura.midolman.mgmt.data.zookeeper.op.RouterOpService;

@RunWith(MockitoJUnitRunner.class)
public class TestRouterLinkDaoAdapter {

    private RouterLinkDaoAdapter testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private RouterZkDao dao;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private RouterOpService opService;

    @Before
    public void setUp() throws Exception {
        testObject = new RouterLinkDaoAdapter(dao, opService);
    }

    @Test
    public void testGetNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        doReturn(false).when(dao).routerLinkExists(id, peerId);

        PeerRouterLink link = testObject.get(id, peerId);

        Assert.assertNull(link);
    }
}
