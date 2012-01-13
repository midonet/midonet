/*
 * @(#)TestVifZkDao        1.6 11/12/25
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
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.mgmt.data.dto.config.VifConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.VifSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.ZkManager;

public class TestVifZkDao {

    private ZkManager zkDaoMock = null;
    private PathBuilder pathBuilderMock = null;
    private VifSerializer serializerMock = null;
    private VifZkDao dao = null;
    private final static String dummyPath = "/foo";
    private final static byte[] dummyBytes = { 1, 2, 3 };
    private final static VifConfig dummyConfig = new VifConfig();
    private static Set<String> dummyIds = null;
    static {
        dummyIds = new TreeSet<String>();
        dummyIds.add("foo");
        dummyIds.add("bar");
    }

    @Before
    public void setUp() throws Exception {
        zkDaoMock = mock(PortZkManager.class);
        pathBuilderMock = mock(PathBuilder.class);
        serializerMock = mock(VifSerializer.class);
        dao = new VifZkDao(zkDaoMock, pathBuilderMock, serializerMock);
    }

    @Test
    public void TestGetDataSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        when(pathBuilderMock.getVifPath(id)).thenReturn(dummyPath);
        when(zkDaoMock.get(dummyPath)).thenReturn(dummyBytes);
        when(serializerMock.deserialize(dummyBytes)).thenReturn(dummyConfig);

        VifConfig config = dao.getData(id);

        Assert.assertEquals(dummyConfig, config);
    }

    @Test
    public void TestGetIdsSuccess() throws Exception {
        when(pathBuilderMock.getVifsPath()).thenReturn(dummyPath);
        when(zkDaoMock.getChildren(dummyPath, null)).thenReturn(dummyIds);

        Set<String> ids = dao.getIds();

        Assert.assertArrayEquals(dummyIds.toArray(), ids.toArray());
    }

    @Test
    public void TestMultiSuccess() throws Exception {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(dummyPath, dummyBytes, null, CreateMode.PERSISTENT));
        ops.add(Op.delete(dummyPath, -1));
        ops.add(Op.setData(dummyPath, dummyBytes, -1));
        dao.multi(ops);
        verify(zkDaoMock, times(1)).multi(ops);
    }
}
