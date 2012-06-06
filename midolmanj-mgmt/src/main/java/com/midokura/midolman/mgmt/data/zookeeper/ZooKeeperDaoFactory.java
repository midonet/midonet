/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper;

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
import com.midokura.midolman.mgmt.data.dao.zookeeper.AdRouteZkProxy;
import com.midokura.midolman.mgmt.data.dao.zookeeper.ApplicationZkDao;
import com.midokura.midolman.mgmt.data.dao.zookeeper.BgpZkProxy;
import com.midokura.midolman.mgmt.data.dao.zookeeper.BridgeDaoAdapter;
import com.midokura.midolman.mgmt.data.dao.zookeeper.BridgeZkDao;
import com.midokura.midolman.mgmt.data.dao.zookeeper.ChainDaoAdapter;
import com.midokura.midolman.mgmt.data.dao.zookeeper.ChainZkDao;
import com.midokura.midolman.mgmt.data.dao.zookeeper.DhcpDaoAdapter;
import com.midokura.midolman.mgmt.data.dao.zookeeper.HostDaoAdapter;
import com.midokura.midolman.mgmt.data.dao.zookeeper.HostZkDao;
import com.midokura.midolman.mgmt.data.dao.zookeeper.MetricCassandraDao;
import com.midokura.midolman.mgmt.data.dao.zookeeper.PortDaoAdapter;
import com.midokura.midolman.mgmt.data.dao.zookeeper.PortGroupDaoAdapter;
import com.midokura.midolman.mgmt.data.dao.zookeeper.PortZkDao;
import com.midokura.midolman.mgmt.data.dao.zookeeper.RouteZkProxy;
import com.midokura.midolman.mgmt.data.dao.zookeeper.RouterDaoAdapter;
import com.midokura.midolman.mgmt.data.dao.zookeeper.RouterZkDao;
import com.midokura.midolman.mgmt.data.dao.zookeeper.RuleZkProxy;
import com.midokura.midolman.mgmt.data.dao.zookeeper.TenantDaoAdapter;
import com.midokura.midolman.mgmt.data.dao.zookeeper.TenantZkDao;
import com.midokura.midolman.mgmt.data.dao.zookeeper.VpnZkProxy;
import com.midokura.midolman.mgmt.data.zookeeper.op.BridgeOpBuilder;
import com.midokura.midolman.mgmt.data.zookeeper.op.BridgeOpService;
import com.midokura.midolman.mgmt.data.zookeeper.op.ChainOpBuilder;
import com.midokura.midolman.mgmt.data.zookeeper.op.ChainOpService;
import com.midokura.midolman.mgmt.data.zookeeper.op.PortOpBuilder;
import com.midokura.midolman.mgmt.data.zookeeper.op.PortOpService;
import com.midokura.midolman.mgmt.data.zookeeper.op.RouterOpBuilder;
import com.midokura.midolman.mgmt.data.zookeeper.op.RouterOpService;
import com.midokura.midolman.mgmt.data.zookeeper.op.TenantOpBuilder;
import com.midokura.midolman.mgmt.data.zookeeper.op.TenantOpService;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathService;
import com.midokura.midolman.mgmt.rest_api.jaxrs.JsonJaxbSerializer;
import com.midokura.midolman.monitoring.store.CassandraStore;
import com.midokura.midolman.state.AdRouteZkManager;
import com.midokura.midolman.state.BgpZkManager;
import com.midokura.midolman.state.BridgeDhcpZkManager;
import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.VpnZkManager;
import com.midokura.midolman.state.ZkConfigSerializer;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midolman.state.ZkPathManager;

/**
 * ZooKeeper DAO factory class.
 */
public class ZooKeeperDaoFactory extends AbstractDaoFactory {

    private final static Logger log = LoggerFactory
            .getLogger(ZooKeeperDaoFactory.class);
    protected Directory directory;
    protected ZkConnection conn;
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
        log.debug("ZooKeeperDaoFactory: Initailizing ZooKeeperDaoFactory");
        this.rootPath = config.getZkRootPath();
        this.rootMgmtPath = config.getZkMgmtRootPath();
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
        if (directory == null || conn == null || !conn.isConnected()) {

            // Reset everything if the state is inconsistent.
            if(conn != null && conn.isConnected()) {
                conn.close();
            }

            try {
                conn = new ZkConnection(connStr, timeout, null);
                conn.open();
            } catch (Exception e) {
                throw new StateAccessException("Failed to open ZK connecion", e);
            }
            directory = conn.getRootDirectory();
        }

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
        return new AdRouteZkProxy(getAdRouteZkManager());
    }

    private AdRouteZkManager getAdRouteZkManager() throws StateAccessException {
        return new AdRouteZkManager(getDirectory(), this.rootPath);
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

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getBgpDao()
     */
    @Override
    public BgpDao getBgpDao() throws StateAccessException {
        return new BgpZkProxy(getBgpZkManager(), getAdRouteDao());
    }

    private BgpZkManager getBgpZkManager() throws StateAccessException {
        return new BgpZkManager(getDirectory(), this.rootPath);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getBridgeDao()
     */
    @Override
    public BridgeDao getBridgeDao() throws StateAccessException {
        return new BridgeDaoAdapter(getBridgeZkDao(), getBridgeOpService(),
                getPortDao());
    }

    private BridgeZkManager getBridgeZkManager() throws StateAccessException {
        return new BridgeZkManager(getDirectory(), getRootPath());
    }

    private BridgeDhcpZkManager getBridgeDhcpZkMaanager()
            throws StateAccessException {
        return new BridgeDhcpZkManager(getDirectory(), getRootPath());
    }

    private BridgeZkDao getBridgeZkDao() throws StateAccessException {
        return new BridgeZkDao(getBridgeZkManager(), getPathBuilder(),
                getSerializer());
    }

    private BridgeOpBuilder getBridgeOpBuilder() throws StateAccessException {
        return new BridgeOpBuilder(getBridgeZkManager(), getPathBuilder(),
                getSerializer());
    }

    private BridgeOpService getBridgeOpService() throws StateAccessException {
        return new BridgeOpService(getBridgeOpBuilder(), getPortOpService(),
                getBridgeZkDao());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getChainDao()
     */
    @Override
    public ChainDao getChainDao() throws StateAccessException {
        return new ChainDaoAdapter(getChainZkDao(), getChainOpService(),
                getRuleDao());
    }

    @Override
    public HostDao getHostDao() throws StateAccessException {
        return new HostDaoAdapter(getHostZkDao());
    }

    private HostZkDao getHostZkDao() throws StateAccessException {
        return new HostZkDao(getHostZkManager(), getPathManager());
    }

    private HostZkManager getHostZkManager() throws StateAccessException {
        return new HostZkManager(getDirectory(), getRootPath());
    }

    private ChainZkDao getChainZkDao() throws StateAccessException {
        return new ChainZkDao(getChainZkManager(), getPathBuilder(),
                getSerializer());
    }

    private ChainZkManager getChainZkManager() throws StateAccessException {
        return new ChainZkManager(getDirectory(), getRootPath());
    }

    private ChainOpBuilder getChainOpBuilder() throws StateAccessException {
        return new ChainOpBuilder(getChainZkManager(), getPathBuilder(),
                getSerializer());
    }

    private ChainOpService getChainOpService() throws StateAccessException {
        return new ChainOpService(getChainOpBuilder(), getChainZkDao());
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

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getPortDao()
     */
    @Override
    public PortDao getPortDao() throws StateAccessException {
        return new PortDaoAdapter(getPortZkDao(), getPortOpService(),
                getBgpDao(), getVpnDao());
    }

    private PortOpBuilder getPortOpBuilder() throws StateAccessException {
        return new PortOpBuilder(getPortZkManager(), getPathBuilder(),
                getSerializer());
    }

    private PortOpService getPortOpService() throws StateAccessException {
        return new PortOpService(getPortOpBuilder(), getPortZkDao());
    }

    private PortZkDao getPortZkDao() throws StateAccessException {
        return new PortZkDao(getPortZkManager(), getPathBuilder(),
                getSerializer());
    }

    private PortZkManager getPortZkManager() throws StateAccessException {
        return new PortZkManager(getDirectory(), getRootPath());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getRouteDao()
     */
    @Override
    public RouteDao getRouteDao() throws StateAccessException {
        return new RouteZkProxy(getRouteZkManager());
    }

    private RouteZkManager getRouteZkManager() throws StateAccessException {
        return new RouteZkManager(getDirectory(), this.rootPath);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getRouterDao()
     */
    @Override
    public RouterDao getRouterDao() throws StateAccessException {
        return new RouterDaoAdapter(getRouterZkDao(), getRouterOpService(),
                getPortDao(), getRouteDao());
    }

    private RouterZkManager getRouterZkManager() throws StateAccessException {
        return new RouterZkManager(getDirectory(), getRootPath());
    }

    private RouterZkDao getRouterZkDao() throws StateAccessException {
        return new RouterZkDao(getRouterZkManager(), getPathBuilder(),
                getSerializer());
    }

    private RouterOpBuilder getRouterOpBuilder() throws StateAccessException {
        return new RouterOpBuilder(getRouterZkManager(), getPathBuilder(),
                getSerializer());
    }

    private RouterOpService getRouterOpService() throws StateAccessException {
        return new RouterOpService(getRouterOpBuilder(), getPortOpService(),
                getRouterZkDao());
    }

    @Override
    public RuleDao getRuleDao() throws StateAccessException {
        return new RuleZkProxy(getRuleZkManager());
    }

    private RuleZkManager getRuleZkManager() throws StateAccessException {
        return new RuleZkManager(getDirectory(), this.rootPath);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getTenantDao()
     */
    @Override
    public TenantDao getTenantDao() throws StateAccessException {
        return new TenantDaoAdapter(getTenantZkDao(), getTenantOpService(),
                getBridgeDao(), getRouterDao(), getChainDao(),
                getPortGroupDao());
    }

    private TenantZkDao getTenantZkDao() throws StateAccessException {
        return new TenantZkDao(getZkDao(), getPathBuilder());
    }

    private TenantOpBuilder getTenantOpBuilder() throws StateAccessException {
        return new TenantOpBuilder(getZkDao(), getPathBuilder());
    }

    private TenantOpService getTenantOpService() throws StateAccessException {
        return new TenantOpService(getTenantOpBuilder(), getBridgeOpService(),
                getRouterOpService(), getTenantZkDao(), getChainOpService(),
                getPortGroupDao());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getVpnDao()
     */
    @Override
    public VpnDao getVpnDao() throws StateAccessException {
        return new VpnZkProxy(getVpnZkManager());
    }

    @Override
    public DhcpDao getDhcpDao() throws StateAccessException {
        return new DhcpDaoAdapter(getBridgeDhcpZkMaanager());
    }

    @Override
    public PortGroupDao getPortGroupDao() throws StateAccessException {
        return new PortGroupDaoAdapter(getZkDao(), getPathBuilder());
    }

    private VpnZkManager getVpnZkManager() throws StateAccessException {
        return new VpnZkManager(getDirectory(), this.rootPath);
    }

    private ZkManager getZkDao() throws StateAccessException {
        return new ZkManager(getDirectory(), getRootPath());
    }

    @Override
    public MetricDao getMetricDao() throws StateAccessException {
        return new MetricCassandraDao(getCassandraStore());
    }

    private CassandraStore getCassandraStore() {
        return new CassandraStore(AppConfig.cassandraServer,
                AppConfig.cassandraCluster,
                AppConfig.cassandraMonitoringKeySpace,
                AppConfig.cassandraMonitoringColumnFamily,
                AppConfig.cassandraReplicationFactor,
                AppConfig.cassandraTtlInSecs);
    }
}
