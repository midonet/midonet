/*
 * @(#)TestBgpZkProxy        1.6 12/01/8
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.mgmt.data.dao.AdRouteDao;
import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.state.BgpZkManager;
import com.midokura.midolman.state.BgpZkManager.BgpConfig;
import com.midokura.midolman.state.ZkNodeEntry;

public class TestBgpZkProxy {

    private AdRouteDao adRouteDaoMock = null;
    private BgpZkManager daoMock = null;
    private BgpZkProxy proxy = null;

    @Before
    public void setUp() throws Exception {
        daoMock = mock(BgpZkManager.class);
        adRouteDaoMock = mock(AdRouteDao.class);
        proxy = new BgpZkProxy(daoMock, adRouteDaoMock);
    }

    private BgpConfig getBgpConfig(UUID portId, int localAs, byte[] ip,
            int peerAs) throws UnknownHostException {
        InetAddress addr = InetAddress.getByAddress(ip);
        return new BgpConfig(portId, localAs, addr, peerAs);
    }

    private ZkNodeEntry<UUID, BgpConfig> getZkNodeEntry(UUID id, UUID portId,
            int localAs, byte[] ip, int peerAs) throws UnknownHostException {
        BgpConfig config = getBgpConfig(portId, localAs, ip, peerAs);
        return new ZkNodeEntry<UUID, BgpConfig>(id, config);
    }

    @Test
    public void testCreateSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        BgpConfig config = getBgpConfig(UUID.randomUUID(), 100, new byte[] {
                10, 0, 0, 1 }, 200);
        Bgp Bgp = mock(Bgp.class);
        when(Bgp.toConfig()).thenReturn(config);
        when(daoMock.create(config)).thenReturn(id);

        UUID newId = proxy.create(Bgp);

        Assert.assertEquals(id, newId);
    }

    @Test
    public void testGetSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, BgpConfig> node = getZkNodeEntry(id,
                UUID.randomUUID(), 100, new byte[] { 10, 0, 0, 1 }, 200);

        when(daoMock.get(id)).thenReturn(node);

        Bgp Bgp = proxy.get(id);

        Assert.assertEquals(id, Bgp.getId());
    }

    @Test
    public void testListSuccess() throws Exception {

        UUID portId = UUID.randomUUID();
        List<ZkNodeEntry<UUID, BgpConfig>> entries = new ArrayList<ZkNodeEntry<UUID, BgpConfig>>();
        entries.add(getZkNodeEntry(UUID.randomUUID(), portId, 100, new byte[] {
                10, 0, 0, 1 }, 200));
        entries.add(getZkNodeEntry(UUID.randomUUID(), portId, 100, new byte[] {
                10, 0, 0, 2 }, 200));
        entries.add(getZkNodeEntry(UUID.randomUUID(), portId, 100, new byte[] {
                10, 0, 0, 3 }, 200));

        when(daoMock.list(portId)).thenReturn(entries);

        List<Bgp> Bgps = proxy.list(portId);

        Assert.assertEquals(3, Bgps.size());
        Assert.assertEquals(entries.size(), Bgps.size());
        Assert.assertEquals(entries.remove(0).key, Bgps.remove(0).getId());
        Assert.assertEquals(entries.remove(0).key, Bgps.remove(0).getId());
        Assert.assertEquals(entries.remove(0).key, Bgps.remove(0).getId());
    }

    @Test
    public void testDeleteSuccess() throws Exception {
        UUID id = UUID.randomUUID();

        proxy.delete(id);

        verify(daoMock, times(1)).delete(id);
    }

    @Test
    public void testGetByAdRouteSuccess() throws Exception {
        UUID adRouteId = UUID.randomUUID();
        ZkNodeEntry<UUID, BgpConfig> node = getZkNodeEntry(UUID.randomUUID(),
                UUID.randomUUID(), 100, new byte[] { 10, 0, 0, 1 }, 200);
        AdRoute adRoute = new AdRoute();
        adRoute.setBgpId(node.key);

        when(adRouteDaoMock.get(adRouteId)).thenReturn(adRoute);
        when(daoMock.get(node.key)).thenReturn(node);

        Bgp bgpOut = proxy.getByAdRoute(adRouteId);

        Assert.assertEquals(node.key, bgpOut.getId());
    }
}
