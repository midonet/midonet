/*
 * @(#)TestAdRouteZkProxy        1.6 12/01/8
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

import com.midokura.midolman.mgmt.data.dto.AdRoute;
import com.midokura.midolman.state.AdRouteZkManager;
import com.midokura.midolman.state.AdRouteZkManager.AdRouteConfig;
import com.midokura.midolman.state.ZkNodeEntry;

public class TestAdRouteZkProxy {

    private AdRouteZkManager daoMock = null;
    private AdRouteZkProxy proxy = null;

    @Before
    public void setUp() throws Exception {
        daoMock = mock(AdRouteZkManager.class);
        proxy = new AdRouteZkProxy(daoMock);
    }

    private AdRouteConfig getAdRouteConfig(UUID id, UUID bgpId, byte[] ip,
            byte prefixLen) throws UnknownHostException {
        InetAddress addr = InetAddress.getByAddress(ip);
        return new AdRouteConfig(bgpId, addr, prefixLen);
    }

    private ZkNodeEntry<UUID, AdRouteConfig> getZkNodeEntry(UUID id,
            UUID bgpId, byte[] ip, byte prefixLen) throws UnknownHostException {
        AdRouteConfig config = getAdRouteConfig(id, bgpId, ip, prefixLen);
        return new ZkNodeEntry<UUID, AdRouteConfig>(id, config);
    }

    @Test
    public void testCreateSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        AdRouteConfig config = getAdRouteConfig(id, UUID.randomUUID(),
                new byte[] { 10, 0, 0, 1 }, (byte) 24);
        AdRoute adRoute = mock(AdRoute.class);
        when(adRoute.toConfig()).thenReturn(config);
        when(daoMock.create(config)).thenReturn(id);

        UUID newId = proxy.create(adRoute);

        Assert.assertEquals(id, newId);
    }

    @Test
    public void testGetSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        ZkNodeEntry<UUID, AdRouteConfig> node = getZkNodeEntry(id,
                UUID.randomUUID(), new byte[] { 10, 0, 0, 1 }, (byte) 24);

        when(daoMock.get(id)).thenReturn(node);

        AdRoute adRoute = proxy.get(id);

        Assert.assertEquals(id, adRoute.getId());
    }

    @Test
    public void testListSuccess() throws Exception {

        UUID bgpId = UUID.randomUUID();
        List<ZkNodeEntry<UUID, AdRouteConfig>> entries = new ArrayList<ZkNodeEntry<UUID, AdRouteConfig>>();
        entries.add(getZkNodeEntry(UUID.randomUUID(), bgpId, new byte[] { 10,
                0, 0, 1 }, (byte) 24));
        entries.add(getZkNodeEntry(UUID.randomUUID(), bgpId, new byte[] { 10,
                0, 0, 2 }, (byte) 24));
        entries.add(getZkNodeEntry(UUID.randomUUID(), bgpId, new byte[] { 10,
                0, 0, 3 }, (byte) 24));

        when(daoMock.list(bgpId)).thenReturn(entries);

        List<AdRoute> adRoutes = proxy.list(bgpId);

        Assert.assertEquals(3, adRoutes.size());
        Assert.assertEquals(entries.size(), adRoutes.size());
        Assert.assertEquals(entries.remove(0).key, adRoutes.remove(0).getId());
        Assert.assertEquals(entries.remove(0).key, adRoutes.remove(0).getId());
        Assert.assertEquals(entries.remove(0).key, adRoutes.remove(0).getId());
    }

    @Test
    public void testDeleteSuccess() throws Exception {
        UUID id = UUID.randomUUID();

        proxy.delete(id);

        verify(daoMock, times(1)).delete(id);
    }
}
