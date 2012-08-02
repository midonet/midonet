/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper;

import com.midokura.midolman.state.zkManagers.*;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.state.HostZkManager;
import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midolman.mgmt.config.InvalidConfigException;
import com.midokura.midolman.mgmt.data.AbstractDaoFactory;
import com.midokura.midolman.mgmt.data.DaoInitializationException;
import com.midokura.midolman.mgmt.data.dao.AdRouteDao;
import com.midokura.midolman.mgmt.data.dao.ApplicationDao;
import com.midokura.midolman.mgmt.data.dao.BgpDao;
import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dao.DhcpDao;
import com.midokura.midolman.mgmt.data.dao.HostDao;
import com.midokura.midolman.mgmt.data.dao.MetricDao;
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dao.PortGroupDao;
import com.midokura.midolman.mgmt.data.dao.RouteDao;
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dao.RuleDao;
import com.midokura.midolman.mgmt.data.dao.TenantDao;
import com.midokura.midolman.mgmt.data.dao.VpnDao;
import com.midokura.midolman.mgmt.data.zookeeper.dao.AdRouteDaoImpl;
import com.midokura.midolman.mgmt.data.zookeeper.dao.ApplicationDaoImpl;
import com.midokura.midolman.mgmt.data.zookeeper.dao.BgpDaoImpl;
import com.midokura.midolman.mgmt.data.zookeeper.dao.BridgeZkDao;
import com.midokura.midolman.mgmt.data.zookeeper.dao.BridgeZkDaoImpl;
import com.midokura.midolman.mgmt.data.zookeeper.dao.ChainZkDao;
import com.midokura.midolman.mgmt.data.zookeeper.dao.ChainZkDaoImpl;
import com.midokura.midolman.mgmt.data.zookeeper.dao.DhcpDaoImpl;
import com.midokura.midolman.mgmt.data.zookeeper.dao.HostDaoImpl;
import com.midokura.midolman.mgmt.data.zookeeper.dao.HostZkDao;
import com.midokura.midolman.mgmt.data.zookeeper.dao.MetricCassandraDao;
import com.midokura.midolman.mgmt.data.zookeeper.dao.PortDaoImpl;
import com.midokura.midolman.mgmt.data.zookeeper.dao.PortGroupZkDao;
import com.midokura.midolman.mgmt.data.zookeeper.dao.PortGroupZkDaoImpl;
import com.midokura.midolman.mgmt.data.zookeeper.dao.RouteDaoImpl;
import com.midokura.midolman.mgmt.data.zookeeper.dao.RouterZkDao;
import com.midokura.midolman.mgmt.data.zookeeper.dao.RouterZkDaoImpl;
import com.midokura.midolman.mgmt.data.zookeeper.dao.RuleDaoImpl;
import com.midokura.midolman.mgmt.data.zookeeper.dao.TenantDaoImpl;
import com.midokura.midolman.mgmt.data.zookeeper.dao.VpnDaoImpl;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathService;
import com.midokura.midolman.mgmt.jaxrs.JsonJaxbSerializer;
import com.midokura.midolman.monitoring.config.MonitoringConfiguration;
import com.midokura.midolman.monitoring.store.CassandraStore;
import com.midokura.midolman.state.zkManagers.BgpZkManager;
import com.midokura.midolman.state.zkManagers.BridgeDhcpZkManager;
import com.midokura.midolman.state.zkManagers.BridgeZkManager;
import com.midokura.midolman.state.zkManagers.ChainZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.zkManagers.PortGroupZkManager;
import com.midokura.midolman.state.zkManagers.RouterZkManager;
import com.midokura.midolman.state.zkManagers.RuleZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.zkManagers.VpnZkManager;
import com.midokura.midolman.state.ZkConfigSerializer;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkManager;

/**
 * ZooKeeper DAO factory class.
 */
public class ZooKeeperDaoFactory extends AbstractDaoFactory implements Watcher {

    private final static Logger log = LoggerFactory
            .getLogger(ZooKeeperDaoFactory.class);

    protected ZkConnection conn;
    protected final String rootPath;
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
        log.debug("ZooKeeperDaoFactory: Initializing ZooKeeperDaoFactory");

        this.rootPath = config.getZkRootPath();
        this.connStr = config.getZkConnectionString();

        try {
            this.timeout = config.getZkTimeout();
        } catch (InvalidConfigException e) {
            throw new DaoInitializationException("Invalid ZK timeout", e);
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
        if (conn == null) {
            try {
                conn = new ZkConnection(connStr, timeout, this);
                conn.open();
            } catch (Exception e) {
                throw new StateAccessException("Failed to open ZK connection",
                        e);
            }
        }

        return conn.getRootDirectory();
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.apache.zookeeper.Watcher#process(org.apache.zookeeper.WatchedEvent)
     */
    @Override
    synchronized public void process(WatchedEvent event) {
        log.debug("ZooKeeperDaoFactory.process: Entered witth event {}",
                event.getState());

        // The ZK client re-connects automatically. However, after it
        // successfully reconnects, if the session had expired, we need to
        // create a new session.
        if (event.getState() == Watcher.Event.KeeperState.Expired
                && conn != null) {
            conn.close();
            conn = null;
        }

        log.debug("ZooKeeperDaoFactory.process: Exiting");
    }

    private ZkConfigSerializer getSerializer() {
        return new ZkConfigSerializer(new JsonJaxbSerializer());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getAdRouteDao()
     */
    @Override
    public AdRouteDao getAdRouteDao() throws StateAccessException {
        return new AdRouteDaoImpl(new AdRouteZkManager(getDirectory(),
                this.rootPath));
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getApplicationDao()
     */
    @Override
    public ApplicationDao getApplicationDao() throws StateAccessException {
        return new ApplicationDaoImpl(getZkDao(), getPathService());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getBgpDao()
     */
    @Override
    public BgpDao getBgpDao() throws StateAccessException {
        return new BgpDaoImpl(new BgpZkManager(getDirectory(), this.rootPath),
                getAdRouteDao());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getBridgeDao()
     */
    @Override
    public BridgeDao getBridgeDao() throws StateAccessException {
        return getBridgeZkDao();
    }

    private BridgeZkDao getBridgeZkDao() throws StateAccessException {
        return new BridgeZkDaoImpl(
                new BridgeZkManager(getDirectory(), rootPath),
                getPathBuilder(), getSerializer(), getPortDao());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getChainDao()
     */
    @Override
    public ChainDao getChainDao() throws StateAccessException {
        return getChainZkDao();
    }

    private ChainZkDao getChainZkDao() throws StateAccessException {
        return new ChainZkDaoImpl(new ChainZkManager(getDirectory(), rootPath),
                getPathBuilder(), getSerializer(), getRuleDao());
    }

    @Override
    public HostDao getHostDao() throws StateAccessException {
        return new HostDaoImpl(new HostZkDao(new HostZkManager(getDirectory(),
                rootPath), getPathBuilder()));
    }

    private PathBuilder getPathBuilder() {
        return new PathBuilder(rootPath);
    }

    private PathService getPathService() {
        return new PathService(getPathBuilder());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getPortDao()
     */
    @Override
    public PortDao getPortDao() throws StateAccessException {
        return new PortDaoImpl(getPortZkManager(), getBgpDao(), getVpnDao());
    }

    private PortZkManager getPortZkManager() throws StateAccessException {
        return new PortZkManager(getDirectory(), rootPath);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getRouteDao()
     */
    @Override
    public RouteDao getRouteDao() throws StateAccessException {
        return new RouteDaoImpl(new RouteZkManager(getDirectory(),
                this.rootPath));
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getRouterDao()
     */
    @Override
    public RouterDao getRouterDao() throws StateAccessException {
        return getRouterZkDao();
    }

    private RouterZkDao getRouterZkDao() throws StateAccessException {
        return new RouterZkDaoImpl(
                new RouterZkManager(getDirectory(), rootPath),
                getPathBuilder(), getSerializer(), getPortDao(), getRouteDao());
    }

    @Override
    public RuleDao getRuleDao() throws StateAccessException {
        return new RuleDaoImpl(new RuleZkManager(getDirectory(), this.rootPath));
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getTenantDao()
     */
    @Override
    public TenantDao getTenantDao() throws StateAccessException {
        return new TenantDaoImpl(getZkDao(), getPathBuilder(),
                getBridgeZkDao(), getRouterZkDao(), getChainZkDao(),
                getPortGroupZkDao());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getVpnDao()
     */
    @Override
    public VpnDao getVpnDao() throws StateAccessException {
        return new VpnDaoImpl(new VpnZkManager(getDirectory(), this.rootPath));
    }

    @Override
    public DhcpDao getDhcpDao() throws StateAccessException {
        return new DhcpDaoImpl(
                new BridgeDhcpZkManager(getDirectory(), rootPath));
    }

    @Override
    public PortGroupDao getPortGroupDao() throws StateAccessException {
        return getPortGroupZkDao();
    }

    private PortGroupZkDao getPortGroupZkDao() throws StateAccessException {
        return new PortGroupZkDaoImpl(new PortGroupZkManager(getDirectory(),
                this.rootPath), getPathBuilder());
    }

    private ZkManager getZkDao() throws StateAccessException {
        return new ZkManager(getDirectory(), rootPath);
    }

    @Override
    public MetricDao getMetricDao() throws StateAccessException {
        return new MetricCassandraDao(getCassandraStore());
    }

    private CassandraStore getCassandraStore() {
        MonitoringConfiguration configuration = getAppConfig()
                .getConfigProvider().getConfig(MonitoringConfiguration.class);

        return new CassandraStore(configuration.getCassandraServers(),
                configuration.getCassandraCluster(),
                configuration.getMonitoringCassandraKeyspace(),
                configuration.getMonitoringCassandraColumnFamily(),
                configuration.getMonitoringCassandraReplicationFactor(),
                configuration.getMonitoringCassandraExpirationTimeout());
    }
}
