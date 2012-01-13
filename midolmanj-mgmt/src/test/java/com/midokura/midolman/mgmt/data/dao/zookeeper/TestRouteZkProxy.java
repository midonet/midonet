/*
 * @(#)TestRouteZkProxy        1.6 12/01/8
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
import java.util.UUID;

import junit.framework.Assert;

import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.ZkNodeEntry;

public class TestRouteZkProxy {

    private RouteZkManager daoMock = null;
    private RouteZkProxy proxy = null;

    @Before
    public void setUp() throws Exception {
        daoMock = mock(RouteZkManager.class);
        proxy = new RouteZkProxy(daoMock);
    }

    private com.midokura.midolman.layer3.Route getRouteConfig(
            int srcNetworkAddr, int srcNetworkLength, int dstNetworkAddr,
            int dstNetworkLength, NextHop nextHop, UUID nextHopPort,
            int nextHopGateway, int weight, String attributes, UUID routerId) {
        return new com.midokura.midolman.layer3.Route(srcNetworkAddr,
                srcNetworkLength, dstNetworkAddr, dstNetworkLength, nextHop,
                nextHopPort, nextHopGateway, weight, attributes, routerId);
    }

    private ZkNodeEntry<UUID, com.midokura.midolman.layer3.Route> getZkNodeEntry(
            UUID id, int srcNetworkAddr, int srcNetworkLength,
            int dstNetworkAddr, int dstNetworkLength, NextHop nextHop,
            UUID nextHopPort, int nextHopGateway, int weight,
            String attributes, UUID routerId) {
        com.midokura.midolman.layer3.Route config = getRouteConfig(
                srcNetworkAddr, srcNetworkLength, dstNetworkAddr,
                dstNetworkLength, nextHop, nextHopPort, nextHopGateway, weight,
                attributes, routerId);
        return new ZkNodeEntry<UUID, com.midokura.midolman.layer3.Route>(id,
                config);
    }

    @Test
    public void testCreateSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        com.midokura.midolman.layer3.Route config = getRouteConfig(167772161,
                24, 167772162, 24, NextHop.PORT, UUID.randomUUID(), 100, 100,
                "foo", UUID.randomUUID());
        Route route = mock(Route.class);
        when(route.toZkRoute()).thenReturn(config);
        when(daoMock.create(config)).thenReturn(id);

        UUID newId = proxy.create(route);

        Assert.assertEquals(id, newId);
    }

    @Test
    public void testGetSuccess() throws Exception {
        ZkNodeEntry<UUID, com.midokura.midolman.layer3.Route> node = getZkNodeEntry(
                UUID.randomUUID(), 167772161, 24, 167772162, 24, NextHop.PORT,
                UUID.randomUUID(), 100, 100, "foo", UUID.randomUUID());

        when(daoMock.get(node.key)).thenReturn(node);

        Route route = proxy.get(node.key);

        Assert.assertEquals(node.key, route.getId());
    }

    @Test
    public void testListSuccess() throws Exception {

        UUID routerId = UUID.randomUUID();
        List<ZkNodeEntry<UUID, com.midokura.midolman.layer3.Route>> entries = new ArrayList<ZkNodeEntry<UUID, com.midokura.midolman.layer3.Route>>();
        entries.add(getZkNodeEntry(UUID.randomUUID(), 167772161, 24, 167772162,
                24, NextHop.PORT, UUID.randomUUID(), 100, 100, "foo",
                UUID.randomUUID()));
        entries.add(getZkNodeEntry(UUID.randomUUID(), 167772163, 24, 167772164,
                24, NextHop.PORT, UUID.randomUUID(), 100, 100, "foo",
                UUID.randomUUID()));
        entries.add(getZkNodeEntry(UUID.randomUUID(), 167772165, 24, 167772166,
                24, NextHop.PORT, UUID.randomUUID(), 100, 100, "foo",
                UUID.randomUUID()));

        when(daoMock.list(routerId)).thenReturn(entries);

        List<Route> routes = proxy.list(routerId);

        Assert.assertEquals(3, routes.size());
        Assert.assertEquals(entries.size(), routes.size());
        Assert.assertEquals(entries.remove(0).key, routes.remove(0).getId());
        Assert.assertEquals(entries.remove(0).key, routes.remove(0).getId());
        Assert.assertEquals(entries.remove(0).key, routes.remove(0).getId());
    }

    @Test
    public void testDeleteSuccess() throws Exception {
        UUID id = UUID.randomUUID();

        proxy.delete(id);

        verify(daoMock, times(1)).delete(id);
    }
}
