/*
 * @(#)TestBridgeZkDao        1.6 12/1/6
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

import com.midokura.midolman.mgmt.data.dto.config.BridgeMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.BridgeNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.BridgeSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.BridgeZkManager;

public class TestBridgeZkDao {

    private BridgeZkManager zkDaoMock = null;
    private PathBuilder pathBuilderMock = null;
    private BridgeSerializer serializerMock = null;
    private BridgeZkDao dao = null;
    private final static String dummyPath = "/foo";
    private final static byte[] dummyBytes = { 1, 2, 3 };
    private final static BridgeMgmtConfig dummyMgmtConfig = new BridgeMgmtConfig();
    private final static BridgeNameMgmtConfig dummyNameMgmtConfig = new BridgeNameMgmtConfig();
    private static Set<String> dummyIds = null;
    static {
        dummyIds = new TreeSet<String>();
        dummyIds.add("foo");
        dummyIds.add("bar");
    }

    @Before
    public void setUp() throws Exception {
        zkDaoMock = mock(BridgeZkManager.class);
        pathBuilderMock = mock(PathBuilder.class);
        serializerMock = mock(BridgeSerializer.class);
        dao = new BridgeZkDao(zkDaoMock, pathBuilderMock, serializerMock);
    }

    @Test
    public void TestGetDataSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        when(pathBuilderMock.getBridgePath(id)).thenReturn(dummyPath);
        when(zkDaoMock.get(dummyPath)).thenReturn(dummyBytes);
        when(serializerMock.deserialize(dummyBytes))
                .thenReturn(dummyMgmtConfig);

        BridgeMgmtConfig config = dao.getData(id);

        Assert.assertEquals(dummyMgmtConfig, config);
    }

    @Test
    public void TestGetNameDataSuccess() throws Exception {
        String tenantId = "foo";
        String name = "bar";
        when(pathBuilderMock.getTenantBridgeNamePath(tenantId, name))
                .thenReturn(dummyPath);
        when(zkDaoMock.get(dummyPath)).thenReturn(dummyBytes);
        when(serializerMock.deserializeName(dummyBytes)).thenReturn(
                dummyNameMgmtConfig);

        BridgeNameMgmtConfig config = dao.getNameData(tenantId, name);

        Assert.assertEquals(dummyNameMgmtConfig, config);
    }

    @Test
    public void TestGetIdsSuccess() throws Exception {
        String tenantId = "foo";
        when(pathBuilderMock.getTenantBridgesPath(tenantId)).thenReturn(
                dummyPath);
        when(zkDaoMock.getChildren(dummyPath, null)).thenReturn(dummyIds);

        Set<String> ids = dao.getIds(tenantId);

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
