/*
 * @(#)TestRouterZkDao        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
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

import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.RouterSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.RouterZkManager;

public class TestRouterZkDao {

    private RouterZkManager zkDaoMock = null;
    private PathBuilder pathBuilderMock = null;
    private RouterSerializer serializerMock = null;
    private RouterZkDao dao = null;
    private final static String dummyPath = "/foo";
    private final static byte[] dummyBytes = { 1, 2, 3 };
    private final static RouterMgmtConfig dummyMgmtConfig = new RouterMgmtConfig();
    private final static PeerRouterConfig dummyPeerConfig = new PeerRouterConfig();
    private final static RouterNameMgmtConfig dummyNameMgmtConfig = new RouterNameMgmtConfig();
    private static Set<String> dummyIds = null;
    static {
        dummyIds = new TreeSet<String>();
        dummyIds.add("foo");
        dummyIds.add("bar");
    }

    @Before
    public void setUp() throws Exception {
        zkDaoMock = mock(RouterZkManager.class);
        pathBuilderMock = mock(PathBuilder.class);
        serializerMock = mock(RouterSerializer.class);
        dao = new RouterZkDao(zkDaoMock, pathBuilderMock, serializerMock);
    }

    @Test
    public void TestGetDataSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        when(pathBuilderMock.getRouterPath(id)).thenReturn(dummyPath);
        when(zkDaoMock.get(dummyPath)).thenReturn(dummyBytes);
        when(serializerMock.deserialize(dummyBytes))
                .thenReturn(dummyMgmtConfig);

        RouterMgmtConfig config = dao.getMgmtData(id);

        Assert.assertEquals(dummyMgmtConfig, config);
    }

    @Test
    public void TestGetNameDataSuccess() throws Exception {
        String tenantId = "foo";
        String name = "bar";
        when(pathBuilderMock.getTenantRouterNamePath(tenantId, name))
                .thenReturn(dummyPath);
        when(zkDaoMock.get(dummyPath)).thenReturn(dummyBytes);
        when(serializerMock.deserializeName(dummyBytes)).thenReturn(
                dummyNameMgmtConfig);

        RouterNameMgmtConfig config = dao.getNameData(tenantId, name);

        Assert.assertEquals(dummyNameMgmtConfig, config);
    }

    @Test
    public void TestGetIdsSuccess() throws Exception {
        String tenantId = "foo";
        when(pathBuilderMock.getTenantRoutersPath(tenantId)).thenReturn(
                dummyPath);
        when(zkDaoMock.getChildren(dummyPath, null)).thenReturn(dummyIds);

        Set<String> ids = dao.getIds(tenantId);

        Assert.assertArrayEquals(dummyIds.toArray(), ids.toArray());
    }

    @Test
    public void TestGetPeerRouterIdsSuccess() throws Exception {
        UUID routerId = UUID.randomUUID();
        when(pathBuilderMock.getRouterRoutersPath(routerId)).thenReturn(
                dummyPath);
        when(zkDaoMock.getChildren(dummyPath, null)).thenReturn(dummyIds);

        Set<String> ids = dao.getPeerRouterIds(routerId);

        Assert.assertArrayEquals(dummyIds.toArray(), ids.toArray());
    }

    @Test
    public void TestGetRouterLinkDataSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        when(pathBuilderMock.getRouterRouterPath(id, peerId)).thenReturn(
                dummyPath);
        when(zkDaoMock.get(dummyPath)).thenReturn(dummyBytes);
        when(serializerMock.deserializePeer(dummyBytes)).thenReturn(
                dummyPeerConfig);

        PeerRouterConfig config = dao.getRouterLinkData(id, peerId);

        Assert.assertEquals(dummyPeerConfig, config);
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

    @Test
    public void TestRouterLinkExistsTrue() throws Exception {
        UUID id = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        when(pathBuilderMock.getRouterRouterPath(id, peerId)).thenReturn(
                dummyPath);
        when(zkDaoMock.exists(dummyPath)).thenReturn(true);

        boolean exists = dao.routerLinkExists(id, peerId);

        Assert.assertEquals(true, exists);
    }

    @Test
    public void TestRouterLinkExistsFalse() throws Exception {
        UUID id = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        when(pathBuilderMock.getRouterRouterPath(id, peerId)).thenReturn(
                dummyPath);
        when(zkDaoMock.exists(dummyPath)).thenReturn(false);

        boolean exists = dao.routerLinkExists(id, peerId);

        Assert.assertEquals(false, exists);
    }

    @Test
    public void TestConstructPeerRouterConfig() throws Exception {
        UUID portId = UUID.randomUUID();
        UUID peerPortId = UUID.randomUUID();
        PeerRouterConfig config = dao.constructPeerRouterConfig(portId,
                peerPortId);
        Assert.assertEquals(portId, config.portId);
        Assert.assertEquals(peerPortId, config.peerPortId);
    }

}
