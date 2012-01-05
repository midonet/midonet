/*
 * @(#)TestPortZkDao        1.6 11/12/25
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

import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.PortSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.ZkNodeEntry;

public class TestPortZkDao {

    private PortZkManager zkDaoMock = null;
    private PathBuilder pathBuilderMock = null;
    private PortSerializer serializerMock = null;
    private PortZkDao dao = null;
    private final static String dummyPath = "/foo";
    private final static byte[] dummyBytes = { 1, 2, 3 };
    private final static PortConfig dummyConfig = new MaterializedRouterPortConfig();
    private final static PortMgmtConfig dummyMgmtConfig = new PortMgmtConfig();
    private static Set<String> dummyIds = null;
    static {
        dummyIds = new TreeSet<String>();
        dummyIds.add("foo");
        dummyIds.add("bar");
    }
    private static List<ZkNodeEntry<UUID, PortConfig>> dummyNodes = null;
    static {
        dummyNodes = new ArrayList<ZkNodeEntry<UUID, PortConfig>>();
        dummyNodes.add(new ZkNodeEntry<UUID, PortConfig>(UUID.randomUUID(),
                dummyConfig));
        dummyNodes.add(new ZkNodeEntry<UUID, PortConfig>(UUID.randomUUID(),
                dummyConfig));
    }

    @Before
    public void setUp() throws Exception {
        zkDaoMock = mock(PortZkManager.class);
        pathBuilderMock = mock(PathBuilder.class);
        serializerMock = mock(PortSerializer.class);
        dao = new PortZkDao(zkDaoMock, pathBuilderMock, serializerMock);
    }

    @Test
    public void TestExistsTrue() throws Exception {
        UUID id = UUID.randomUUID();
        when(pathBuilderMock.getPortPath(id)).thenReturn(dummyPath);
        when(zkDaoMock.exists(dummyPath)).thenReturn(true);

        boolean exists = dao.exists(id);

        Assert.assertEquals(true, exists);
    }

    @Test
    public void TestExistsFalse() throws Exception {
        UUID id = UUID.randomUUID();
        when(pathBuilderMock.getPortPath(id)).thenReturn(dummyPath);
        when(zkDaoMock.exists(dummyPath)).thenReturn(false);

        boolean exists = dao.exists(id);

        Assert.assertEquals(false, exists);
    }

    @Test
    public void TestGetDataSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        when(zkDaoMock.get(id)).thenReturn(
                new ZkNodeEntry<UUID, PortConfig>(id, dummyConfig));

        PortConfig config = dao.getData(id);

        Assert.assertEquals(dummyConfig, config);
    }

    @Test
    public void TestGetMgmtDataSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        when(pathBuilderMock.getPortPath(id)).thenReturn(dummyPath);
        when(zkDaoMock.get(dummyPath)).thenReturn(dummyBytes);
        when(serializerMock.deserialize(dummyBytes))
                .thenReturn(dummyMgmtConfig);

        PortMgmtConfig config = dao.getMgmtData(id);

        Assert.assertEquals(dummyMgmtConfig, config);
    }

    @Test
    public void TestGetRouterPortIdsSuccess() throws Exception {
        UUID routerId = UUID.randomUUID();
        when(zkDaoMock.listRouterPorts(routerId)).thenReturn(dummyNodes);

        Set<UUID> ids = dao.getRouterPortIds(routerId);

        Assert.assertEquals(dummyNodes.size(), ids.size());
        Assert.assertEquals(dummyNodes.get(0).key, ids.toArray()[0]);
        Assert.assertEquals(dummyNodes.get(1).key, ids.toArray()[1]);
    }

    @Test
    public void TestGetBridgePortIdsSuccess() throws Exception {
        UUID bridgeId = UUID.randomUUID();
        when(zkDaoMock.listBridgePorts(bridgeId)).thenReturn(dummyNodes);

        Set<UUID> ids = dao.getBridgePortIds(bridgeId);

        Assert.assertEquals(dummyNodes.size(), ids.size());
        Assert.assertEquals(dummyNodes.get(0).key, ids.toArray()[0]);
        Assert.assertEquals(dummyNodes.get(1).key, ids.toArray()[1]);
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
