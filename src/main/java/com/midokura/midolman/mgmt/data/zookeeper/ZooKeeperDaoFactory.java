/*
 * @(#)ZooKeeperDaoFactory        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper;

import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midolman.mgmt.config.InvalidConfigException;
import com.midokura.midolman.mgmt.data.AbstractDaoFactory;
import com.midokura.midolman.mgmt.data.DaoInitializationException;
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
import com.midokura.midolman.state.ZkConnection;

public class ZooKeeperDaoFactory extends AbstractDaoFactory {

    private ZkConnection zk = null;
    private String rootPath = null;
    private String rootMgmtPath = null;

    public ZooKeeperDaoFactory(AppConfig config) {
        super(config);
    }

    @Override
    public void initialize() throws DaoInitializationException {
        try {
            rootPath = config.getZkRootPath();
            rootMgmtPath = config.getZkMgmtRootPath();
            zk = new ZkConnection(config.getZkConnectionString(),
                    config.getZkTimeout(), null);
            zk.open();
        } catch (InvalidConfigException e) {
            throw new DaoInitializationException("Invalid configurations", e);
        } catch (Exception e) {
            throw new DaoInitializationException("Failed to open ZK connecion",
                    e);
        }
    }

    /**
     * @return the rootPath
     */
    public String getRootPath() {
        return rootPath;
    }

    /**
     * @param rootPath
     *            the rootPath to set
     */
    public void setRootPath(String rootPath) {
        this.rootPath = rootPath;
    }

    /**
     * @return the rootMgmtPath
     */
    public String getRootMgmtPath() {
        return rootMgmtPath;
    }

    /**
     * @param rootMgmtPath
     *            the rootMgmtPath to set
     */
    public void setRootMgmtPath(String rootMgmtPath) {
        this.rootMgmtPath = rootMgmtPath;
    }

    /**
     * @return the zk
     */
    public ZkConnection getZk() {
        return zk;
    }

    /**
     * @param zk
     *            the zk to set
     */
    public void setZk(ZkConnection zk) {
        this.zk = zk;
    }

    @Override
    public AdminDao getAdminDao() {
        return new AdminZkManager(zk.getRootDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public AdRouteDao getAdRouteDao() {
        return new AdRouteZkManagerProxy(zk.getRootDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public BgpDao getBgpDao() {
        return new BgpZkManagerProxy(zk.getRootDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public BridgeDao getBridgeDao() {
        return new BridgeZkManagerProxy(zk.getRootDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public ChainDao getChainDao() {
        return new ChainZkManagerProxy(zk.getRootDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public PortDao getPortDao() {
        return new PortZkManagerProxy(zk.getRootDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public RouteDao getRouteDao() {
        return new RouteZkManagerProxy(zk.getRootDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public RouterDao getRouterDao() {
        return new RouterZkManagerProxy(zk.getRootDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public RuleDao getRuleDao() {
        return new RuleZkManagerProxy(zk.getRootDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public TenantDao getTenantDao() {
        return new TenantZkManager(zk.getRootDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public VifDao getVifDao() {
        return new VifZkManager(zk.getRootDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public VpnDao getVpnDao() {
        return new VpnZkManagerProxy(zk.getRootDirectory(), this.rootPath,
                this.rootMgmtPath);
    }
}
