/*
 * @(#)MockDaoFactory        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data;

import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midolman.mgmt.config.InvalidConfigException;
import com.midokura.midolman.mgmt.data.dao.AdRouteDao;
import com.midokura.midolman.mgmt.data.dao.AdminDao;
import com.midokura.midolman.mgmt.data.dao.BgpDao;
import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dao.RouteDao;
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dao.RuleDao;
import com.midokura.midolman.mgmt.data.dao.TenantDao;
import com.midokura.midolman.mgmt.data.dao.VifDao;
import com.midokura.midolman.mgmt.data.dao.VpnDao;
import com.midokura.midolman.mgmt.data.dao.zookeeper.AdRouteZkManagerProxy;
import com.midokura.midolman.mgmt.data.dao.zookeeper.AdminZkManager;
import com.midokura.midolman.mgmt.data.dao.zookeeper.BgpZkManagerProxy;
import com.midokura.midolman.mgmt.data.dao.zookeeper.BridgeZkManagerProxy;
import com.midokura.midolman.mgmt.data.dao.zookeeper.ChainZkManagerProxy;
import com.midokura.midolman.mgmt.data.dao.zookeeper.PortZkManagerProxy;
import com.midokura.midolman.mgmt.data.dao.zookeeper.RouteZkManagerProxy;
import com.midokura.midolman.mgmt.data.dao.zookeeper.RouterZkManagerProxy;
import com.midokura.midolman.mgmt.data.dao.zookeeper.RuleZkManagerProxy;
import com.midokura.midolman.mgmt.data.dao.zookeeper.TenantZkManager;
import com.midokura.midolman.mgmt.data.dao.zookeeper.VifZkManager;
import com.midokura.midolman.mgmt.data.dao.zookeeper.VpnZkManagerProxy;
import com.midokura.midolman.state.MockDirectory;

// This test code is included under src/main/java because it can be useful
// for in-memory testing in other projects that depend on midolmanj-mgmt.
// Code under src/main/java is not included in the midolmanj-mgmt jar.
public class MockDaoFactory extends AbstractDaoFactory {

    private MockDirectory zk = null;
    private String rootPath = null;
    private String rootMgmtPath = null;

    public MockDaoFactory(AppConfig config) {
        super(config);
    }

    @Override
    public void initialize() throws DaoInitializationException {
        this.zk = new MockDirectory();
        try {
            this.rootPath = this.config.getZkRootPath();
            this.rootMgmtPath = this.config.getZkMgmtRootPath();
        } catch (InvalidConfigException e) {
            throw new DaoInitializationException("Invalid configurations", e);
        }
    }

    @Override
    public AdminDao getAdminDao() {
        return new AdminZkManager(this.zk, this.rootPath, this.rootMgmtPath);
    }

    @Override
    public AdRouteDao getAdRouteDao() {
        return new AdRouteZkManagerProxy(this.zk, this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public BgpDao getBgpDao() {
        return new BgpZkManagerProxy(this.zk, this.rootPath, this.rootMgmtPath);
    }

    @Override
    public BridgeDao getBridgeDao() {
        return new BridgeZkManagerProxy(this.zk, this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public ChainDao getChainDao() {
        return new ChainZkManagerProxy(this.zk, this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public PortDao getPortDao() {
        return new PortZkManagerProxy(this.zk, this.rootPath, this.rootMgmtPath);
    }

    @Override
    public RouteDao getRouteDao() {
        return new RouteZkManagerProxy(this.zk, this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public RouterDao getRouterDao() {
        return new RouterZkManagerProxy(this.zk, this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public RuleDao getRuleDao() {
        return new RuleZkManagerProxy(this.zk, this.rootPath, this.rootMgmtPath);
    }

    @Override
    public TenantDao getTenantDao() {
        return new TenantZkManager(this.zk, this.rootPath, this.rootMgmtPath);
    }

    @Override
    public VifDao getVifDao() {
        return new VifZkManager(this.zk, this.rootPath, this.rootMgmtPath);
    }

    @Override
    public VpnDao getVpnDao() {
        return new VpnZkManagerProxy(this.zk, this.rootPath, this.rootMgmtPath);

    }
}
