/*
 * @(#)ZooKeeperDaoFactory        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midolman.mgmt.config.InvalidConfigException;
import com.midokura.midolman.mgmt.data.AbstractDaoFactory;
import com.midokura.midolman.mgmt.data.DaoInitializationException;
import com.midokura.midolman.mgmt.data.dao.AdRouteDao;
import com.midokura.midolman.mgmt.data.dao.ApplicationDao;
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
import com.midokura.midolman.mgmt.data.dao.zookeeper.ApplicationZkDao;
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
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathService;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midolman.state.ZkPathManager;

/**
 * ZooKeeper DAO factory class.
 *
 * @version 1.6 15 Nov 2011
 * @author Ryu Ishimoto
 */
public class ZooKeeperDaoFactory extends AbstractDaoFactory {

    private final static Logger log = LoggerFactory
            .getLogger(ZooKeeperDaoFactory.class);
    protected Directory directory = null;
    protected final String rootPath;
    protected final String rootMgmtPath;
    protected final String connStr;
    protected final int timeout;

    /**
     * Constructor
     *
     * @param config
     *            AppConfig object to initialize ZooKeeperDaoFactory.
     * @throws DaoInitializationException
     *             Initialization error.
     */
    public ZooKeeperDaoFactory(AppConfig config)
            throws DaoInitializationException {
        super(config);
        try {
            this.rootPath = config.getZkRootPath();
            this.rootMgmtPath = config.getZkMgmtRootPath();
            this.connStr = config.getZkConnectionString();
            this.timeout = config.getZkTimeout();
        } catch (InvalidConfigException e) {
            throw new DaoInitializationException("Invalid configurations", e);
        }
    }

    /**
     * Get the Directory object. Override this method to use a mock Directory.
     *
     * @return Directory object.
     * @throws StateAccessException
     *             Data access error.
     */
    synchronized public Directory getDirectory() throws StateAccessException {
        log.debug(
                "ZooKeeperDaoFactory.getDirectory entered: (directory==null)? {}",
                (directory == null));

        if (directory == null) {
            ZkConnection zk = null;
            try {
                zk = new ZkConnection(connStr, timeout, null);
                zk.open();
            } catch (Exception e) {
                throw new StateAccessException("Failed to open ZK connecion", e);
            }
            directory = zk.getRootDirectory();
        }

        log.debug(
                "ZooKeeperDaoFactory.getDirectory exiting: (directory==null)? {}",
                (directory == null));
        return directory;
    }

    /**
     * @return the rootPath
     */
    public String getRootPath() {
        return rootPath;
    }

    /**
     * @return the rootMgmtPath
     */
    public String getRootMgmtPath() {
        return rootMgmtPath;
    }

    @Override
    public AdRouteDao getAdRouteDao() throws StateAccessException {
        return new AdRouteZkManagerProxy(getDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getApplicationDao()
     */
    @Override
    public ApplicationDao getApplicationDao() throws StateAccessException {
        return new ApplicationZkDao(getZkDao(), getPathService());
    }

    @Override
    public BgpDao getBgpDao() throws StateAccessException {
        return new BgpZkManagerProxy(getDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public BridgeDao getBridgeDao() throws StateAccessException {
        return new BridgeZkManagerProxy(getDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public ChainDao getChainDao() throws StateAccessException {
        return new ChainZkManagerProxy(getDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    private PathBuilder getPathBuilder() {
        return new PathBuilder(rootMgmtPath);
    }

    private ZkPathManager getPathManager() {
        return new ZkPathManager(rootPath);
    }

    private PathService getPathService() {
        return new PathService(getPathManager(), getPathBuilder());
    }

    @Override
    public PortDao getPortDao() throws StateAccessException {
        return new PortZkManagerProxy(getDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public RouteDao getRouteDao() throws StateAccessException {
        return new RouteZkManagerProxy(getDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public RouterDao getRouterDao() throws StateAccessException {
        return new RouterZkManagerProxy(getDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public RuleDao getRuleDao() throws StateAccessException {
        return new RuleZkManagerProxy(getDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public TenantDao getTenantDao() throws StateAccessException {
        return new TenantZkManager(getDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public VifDao getVifDao() throws StateAccessException {
        return new VifZkManager(getDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    @Override
    public VpnDao getVpnDao() throws StateAccessException {
        return new VpnZkManagerProxy(getDirectory(), this.rootPath,
                this.rootMgmtPath);
    }

    private ZkManager getZkDao() throws StateAccessException {
        return new ZkManager(getDirectory(), getRootPath());
    }
}
