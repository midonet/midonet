/*
 * @(#)TestTenantZkDao        1.6 12/1/6
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.ZkManager;

public class TestTenantZkDao {

    private ZkManager zkDaoMock = null;
    private PathBuilder pathBuilderMock = null;
    private TenantZkDao dao = null;
    private final static String dummyPath = "/foo";
    private final static byte[] dummyBytes = { 1, 2, 3 };
    private static Set<String> dummyIds = null;
    static {
        dummyIds = new TreeSet<String>();
        dummyIds.add("foo");
        dummyIds.add("bar");
    }

    @Before
    public void setUp() throws Exception {
        zkDaoMock = mock(ZkManager.class);
        pathBuilderMock = mock(PathBuilder.class);
        dao = new TenantZkDao(zkDaoMock, pathBuilderMock);
    }

    @Test
    public void testGetDataSuccess() throws Exception {
        String id = "foo";
        when(pathBuilderMock.getTenantPath(id)).thenReturn(dummyPath);
        when(zkDaoMock.get(dummyPath)).thenReturn(dummyBytes);

        byte[] data = dao.getData(id);

        Assert.assertEquals(dummyBytes, data);
    }

    @Test
    public void testGetIdsSuccess() throws Exception {
        when(pathBuilderMock.getTenantsPath()).thenReturn(dummyPath);
        when(zkDaoMock.getChildren(dummyPath, null)).thenReturn(dummyIds);

        Set<String> ids = dao.getIds();

        Assert.assertArrayEquals(dummyIds.toArray(), ids.toArray());
    }

    @Test
    public void testMultiSuccess() throws Exception {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(dummyPath, dummyBytes, null, CreateMode.PERSISTENT));
        ops.add(Op.delete(dummyPath, -1));
        ops.add(Op.setData(dummyPath, dummyBytes, -1));
        dao.multi(ops);
        verify(zkDaoMock, times(1)).multi(ops);
    }

    @Test
    public void testExistsTrue() throws Exception {
        String id = "foo";
        when(pathBuilderMock.getTenantPath(id)).thenReturn(dummyPath);
        when(zkDaoMock.exists(dummyPath)).thenReturn(true);

        boolean exists = dao.exists(id);

        Assert.assertEquals(true, exists);
    }

    @Test
    public void testExistsFalse() throws Exception {
        String id = "foo";
        when(pathBuilderMock.getTenantPath(id)).thenReturn(dummyPath);
        when(zkDaoMock.exists(dummyPath)).thenReturn(false);

        boolean exists = dao.exists(id);

        Assert.assertEquals(false, exists);
    }
}
