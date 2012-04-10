/*
 * @(#)TestRouterLinkDaoAdapter        1.6 12/01/10
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import junit.framework.Assert;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.mgmt.data.dto.LogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.PeerRouterLink;
import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.data.zookeeper.op.RouterOpService;
import com.midokura.midolman.state.PortConfig;

public class TestRouterLinkDaoAdapter {

    private RouterZkDao daoMock = null;
    private RouterOpService opServiceMock = null;
    private RouterLinkDaoAdapter adapter = null;

    private static LogicalRouterPort createTestLogicalPort(UUID id,
            UUID routerId, UUID peerId, UUID peerRouterId) {
        LogicalRouterPort port = new LogicalRouterPort();
        port.setId(id);
        port.setDeviceId(routerId);
        port.setNetworkAddress("192.168.100.0");
        port.setNetworkLength(24);
        port.setPeerId(peerId);
        port.setPeerPortAddress("196.168.200.2");
        port.setPeerRouterId(peerRouterId);
        port.setPortAddress("192.168.200.1");
        return port;
    }

    private static List<Op> createTestPersistentCreateOps() {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op
                .create("/foo", new byte[] { 0 }, null, CreateMode.PERSISTENT));
        ops.add(Op
                .create("/bar", new byte[] { 1 }, null, CreateMode.PERSISTENT));
        ops.add(Op
                .create("/baz", new byte[] { 2 }, null, CreateMode.PERSISTENT));
        return ops;
    }

    private static List<Op> createTestDeleteOps() {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete("/foo", -1));
        ops.add(Op.delete("/bar", -1));
        ops.add(Op.delete("/baz", -1));
        return ops;
    }

    private static Set<String> createTestIds(int count) {
        Set<String> ids = new TreeSet<String>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID().toString());
        }
        return ids;
    }

    @Before
    public void setUp() throws Exception {
        daoMock = mock(RouterZkDao.class);
        opServiceMock = mock(RouterOpService.class);
        adapter = spy(new RouterLinkDaoAdapter(daoMock, opServiceMock));
    }

    @Test
    public void testCreateSuccess() throws Exception {
        LogicalRouterPort port = createTestLogicalPort(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<Op> ops = createTestPersistentCreateOps();

        doReturn(false).when(daoMock).routerLinkExists(port.getDeviceId(),
                port.getPeerRouterId());
        doReturn(ops).when(opServiceMock).buildLink(any(UUID.class),
                any(PortConfig.class), any(UUID.class), any(PortConfig.class));

        PeerRouterLink link = adapter.create(port);

        Assert.assertEquals(port.getDeviceId(), link.getRouterId());
        Assert.assertEquals(port.getPeerRouterId(), link.getPeerRouterId());
        Assert.assertEquals(port.getId(), link.getPortId());
        Assert.assertEquals(port.getPeerId(), link.getPeerPortId());
        verify(daoMock, times(1)).multi(ops);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateLinkAlreadyExists() throws Exception {
        LogicalRouterPort port = createTestLogicalPort(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        doReturn(true).when(daoMock).routerLinkExists(port.getDeviceId(),
                port.getPeerRouterId());

        adapter.create(port);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateBadInputError() throws Exception {
        LogicalRouterPort port = createTestLogicalPort(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null);

        adapter.create(port);
    }

    @Test
    public void testDeleteSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        List<Op> ops = createTestDeleteOps();

        doReturn(true).when(daoMock).routerLinkExists(id, peerId);
        doReturn(ops).when(opServiceMock).buildUnlink(id, peerId);

        adapter.delete(id, peerId);

        verify(daoMock, times(1)).multi(ops);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDeleteDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();

        doReturn(false).when(daoMock).routerLinkExists(id, peerId);

        adapter.delete(id, peerId);
    }

    @Test
    public void testGetSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        PeerRouterConfig config = new PeerRouterConfig(id, peerId);

        doReturn(config).when(daoMock).getRouterLinkData(id, peerId);
        doReturn(true).when(daoMock).routerLinkExists(id, peerId);

        PeerRouterLink link = adapter.get(id, peerId);

        Assert.assertEquals(config.portId, link.getPortId());
        Assert.assertEquals(config.peerPortId, link.getPeerPortId());
    }

    @Test
    public void testListSuccess() throws Exception {

        Set<String> ids = createTestIds(3);
        UUID routerId = UUID.randomUUID();

        doReturn(ids).when(daoMock).getPeerRouterIds(routerId);
        for (String id : ids) {
            UUID uuid = UUID.fromString(id);
            PeerRouterLink link = new PeerRouterLink(UUID.randomUUID(),
                    UUID.randomUUID(), routerId, uuid);
            doReturn(link).when(adapter).get(routerId, uuid);
        }

        List<PeerRouterLink> links = adapter.list(routerId);

        Assert.assertEquals(3, links.size());
        for (int i = 0; i < links.size(); i++) {
            Assert.assertTrue(ids.contains(links.get(i).getPeerRouterId()
                    .toString()));
            Assert.assertEquals(routerId, links.get(i).getRouterId());
        }
    }
}
