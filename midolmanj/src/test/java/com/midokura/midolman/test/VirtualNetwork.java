/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.test;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import com.midokura.midolman.Setup;
import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.StateAccessException;

public class VirtualNetwork {
    private RouterZkManager routerManager;
    private BridgeZkManager bridgeManager;
    private PortZkManager portManager;
    private RouteZkManager rtManager;
    private ChainZkManager chainManager;
    private RuleZkManager ruleManager;

    private final String basePath = "/midonet";
    private final Directory directory;

    public VirtualNetwork()
            throws InterruptedException, KeeperException, StateAccessException {
        directory = new MockDirectory();
        directory.add(basePath, null, CreateMode.PERSISTENT);
        Setup.createZkDirectoryStructure(directory, basePath);
        routerManager = new RouterZkManager(directory, basePath);
        bridgeManager = new BridgeZkManager(directory, basePath);
        portManager = new PortZkManager(directory, basePath);
        rtManager = new RouteZkManager(directory, basePath);
        chainManager = new ChainZkManager(directory, basePath);
        ruleManager = new RuleZkManager(directory, basePath);
    }

    BridgeZkManager getBridgeManager() {
        return bridgeManager;
    }

    RouterZkManager getRouterManager() {
        return routerManager;
    }

    RouteZkManager getRouteManager() {
        return rtManager;
    }

    PortZkManager getPortManager() {
        return portManager;
    }

    ChainZkManager getChainManager() {
        return chainManager;
    }

    RuleZkManager getRuleManager() {
        return ruleManager;
    }

    public Router makeRouter() throws StateAccessException {
        return new Router(this);
    }

    public Bridge makeBridge() throws StateAccessException {
        return new Bridge(this);
    }

    public Directory getBaseDirectory() {
        return directory;
    }

    public String getBasePath() {
        return basePath;
    }
}
