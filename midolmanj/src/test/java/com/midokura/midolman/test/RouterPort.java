/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.test;

import java.util.UUID;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.RouterPortConfig;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;

public class RouterPort {
    UUID portID;
    Router router;
    RouterPortConfig portConfig;

    public RouterPort(UUID portID, Router router)
            throws StateAccessException {
        this.portID = portID;
        this.router = router;
        this.portConfig = router.network.getPortManager().get(
                portID, RouterPortConfig.class);
    }

    public static RouterPort makeMaterialized(Router router,
            IntIPv4 portAddr, int nwLength) throws StateAccessException {
        MaterializedRouterPortConfig portConfig =
                new MaterializedRouterPortConfig(
                        router.routerID, portAddr.addressAsInt(), nwLength,
                        portAddr.addressAsInt(), MAC.random(), null,
                        portAddr.addressAsInt(), nwLength, null);
        UUID portID = router.network.getPortManager().create(portConfig);
        return new RouterPort(portID, router);
    }

    public void addRoute(String dstPrefix, int dstLength, String gwAddr)
            throws StateAccessException {
        Route rt = new Route(
                0, 0, IntIPv4.fromString(dstPrefix).getAddress(), dstLength,
                Route.NextHop.PORT, portID,
                gwAddr == null? 0 : IntIPv4.fromString(gwAddr).getAddress(),
                1, null, router.routerID);
        router.network.getRouteManager().create(rt);
    }

    public IntIPv4 getAddress() {
        return new IntIPv4(portConfig.portAddr);
    }

    public UUID getId() {
        return portID;
    }

    public void addInboundFilter() throws StateAccessException {
        portConfig.inboundFilter = router.network.getChainManager().create(
                new ChainZkManager.ChainConfig("DUMMY"));
        router.network.getPortManager().update(portID, portConfig);
    }

    public RouterPortConfig getConfig() {
        return portConfig;
    }
}
