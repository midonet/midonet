/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.eventloop.MockReactor;
import com.midokura.midolman.layer3.ReplicatedRoutingTable;
import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.layer3.Router;

public class TestRouterZkManager {

    private UUID uplinkId;
    private int uplinkGatewayAddr;
    private int uplinkPortAddr;
    private Route uplinkRoute;
    private Router rtr;
    private ReplicatedRoutingTable rTable;
    private MockReactor reactor;
    private Map<Integer, PortDirectory.MaterializedRouterPortConfig> portConfigs;
    private Map<Integer, UUID> portNumToId;
    ChainZkManager chainMgr;
    RuleZkManager ruleMgr;

    RouterZkManager routerMgr;
    UUID rtrId;
    List<Route> routes = new ArrayList<Route>();

    /* This setUp() is basically copied from TestRouter.java and
     * modified the following parts:
     *  - routerMgr is changed to a instance variable instead of
     *    local variable because it is needed to call delete() inside testDeleteRouter().
     *  - removed L3DevicePort
     *  - List<Route> routes is added to remove routes in testDeleteRouter().
     */
    @Before
    public void setUp() throws Exception {
        String basePath = "/midolman";
        ZkPathManager pathMgr = new ZkPathManager(basePath);
        Directory dir = new MockDirectory();
        dir.add(pathMgr.getBasePath(), null, CreateMode.PERSISTENT);
        // Add the paths for rules and chains
        dir.add(pathMgr.getChainsPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getFiltersPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRulesPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRoutersPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRoutesPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getPortsPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getGrePath(), null, CreateMode.PERSISTENT);
        PortZkManager portMgr = new PortZkManager(dir, basePath);
        RouteZkManager routeMgr = new RouteZkManager(dir, basePath);
        routerMgr = new RouterZkManager(dir, basePath);
        chainMgr = new ChainZkManager(dir, basePath);
        ruleMgr = new RuleZkManager(dir, basePath);

        rtrId = routerMgr.create();
        reactor = new MockReactor();
        rTable = new ReplicatedRoutingTable(rtrId,
                        routerMgr.getRoutingTableDirectory(rtrId),
                        CreateMode.EPHEMERAL);
        rTable.start();

        // TODO(pino): pass a MockVRNController to the Router.
        rtr = new Router(rtrId, dir, basePath, reactor, null, null);

        // Create ports in ZK.
        // Create one port that works as an uplink for the router.
        uplinkGatewayAddr = 0x0a0b0c0d;
        uplinkPortAddr = 0xb4000102; // 180.0.1.2
        int nwAddr = 0x00000000; // 0.0.0.0/0
        PortDirectory.MaterializedRouterPortConfig portConfig =
                new PortDirectory.MaterializedRouterPortConfig(
                        rtrId, nwAddr, 0, uplinkPortAddr, null, null, nwAddr,
                        0, null);
        uplinkId = portMgr.create(portConfig);
        uplinkRoute = new Route(nwAddr, 0, nwAddr, 0, NextHop.PORT, uplinkId,
                uplinkGatewayAddr, 1, null, rtrId);
        routeMgr.create(uplinkRoute);
        // Pretend the uplink port is managed by a remote controller.
        rTable.addRoute(uplinkRoute);
        routes.add(uplinkRoute);

        // Create ports with id 0, 1, 2, 10, 11, 12, 20, 21, 22.
        // 1, 2, 3 will be in subnet 10.0.0.0/24
        // 11, 12, 13 will be in subnet 10.0.1.0/24
        // 21, 22, 23 will be in subnet 10.0.2.0/24
        // Each of the ports 'spoofs' a /30 range of its subnet, for example
        // port 21 will route to 10.0.2.4/30, 22 to 10.0.2.8/30, etc.
        portConfigs = new HashMap<Integer, PortDirectory.MaterializedRouterPortConfig>();
        portNumToId = new HashMap<Integer, UUID>();
        for (int i = 0; i < 3; i++) {
            // Nw address is 10.0.<i>.0/24
            nwAddr = 0x0a000000 + (i << 8);
            // All ports in this subnet share the same ip address: 10.0.<i>.1
            int portAddr = nwAddr + 1;
            for (int j = 1; j < 4; j++) {
                int portNum = i * 10 + j;
                // The port will route to 10.0.<i>.<j*4>/30
                int segmentAddr = nwAddr + (j * 4);
                portConfig = new PortDirectory.MaterializedRouterPortConfig(
                        rtrId, nwAddr, 24, portAddr, null, null, segmentAddr,
                        30, null);
                portConfigs.put(portNum, portConfig);
                UUID portId = portMgr.create(portConfig);
                // Map the port number to the portId for later use.
                portNumToId.put(portNum, portId);
                // Default route to port based on destination only. Weight 2.
                Route rt = new Route(0, 0, segmentAddr, 30, NextHop.PORT, portId,
                        Route.NO_GATEWAY, 2, null, rtrId);
                routeMgr.create(rt);
                if (1 == j) {
                    // The first port's routes are added manually because the
                    // first port will be treated as remote.
                    rTable.addRoute(rt);
                    routes.add(rt);
                }
                // Anything from 10.0.0.0/16 is allowed through. Weight 1.
                rt = new Route(0x0a000000, 16, segmentAddr, 30, NextHop.PORT,
                        portId, Route.NO_GATEWAY, 1, null, rtrId);
                routeMgr.create(rt);
                if (1 == j) {
                    // The first port's routes are added manually because the
                    // first port will be treated as remote.
                    rTable.addRoute(rt);
                    routes.add(rt);
                }
                // Anything from 11.0.0.0/24 is silently dropped. Weight 1.
                rt = new Route(0x0b000000, 24, segmentAddr, 30,
                        NextHop.BLACKHOLE, null, 0, 1, null, rtrId);
                routeMgr.create(rt);
                // Anything from 12.0.0.0/24 is rejected (ICMP filter
                // prohibited).
                rt = new Route(0x0c000000, 24, segmentAddr, 30, NextHop.REJECT,
                        null, 0, 1, null, rtrId);
                routeMgr.create(rt);

            } // end for-loop on j
        } // end for-loop on i
    }

    @Test
    public void testDeleteRouter(){
        try {
            // Remove entries under routing_table for materialized ports; in the real
            // world, these entries should be removed eventually by the controller once
            // ports have been deleted by terminating VM.
            for(Route rt: routes) {
                rTable.deleteRoute(rt);
            }
            // Now delete the router
            routerMgr.delete(rtrId);
        } catch (Exception e) {
	        e.printStackTrace();
	        Assert.fail("failed");
        } finally {
            ;
        }
    }
}
