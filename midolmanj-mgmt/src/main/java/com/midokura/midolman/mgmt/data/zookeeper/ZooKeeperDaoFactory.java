/*
 * @(#)ZooKeeperDaoFactory        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.state.HostZkManager;
import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midolman.mgmt.config.InvalidConfigException;
import com.midokura.midolman.mgmt.data.AbstractDaoFactory;
import com.midokura.midolman.mgmt.data.DaoInitializationException;
import com.midokura.midolman.mgmt.data.dao.*;
import com.midokura.midolman.mgmt.data.dao.zookeeper.*;
import com.midokura.midolman.mgmt.data.dto.config.BridgeMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.BridgeNameMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.ChainMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.ChainNameMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.PeerRouterConfig;
import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.VifConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.BridgeSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.io.ChainSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.io.PortSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.io.RouterSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.io.VifSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.op.*;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathService;
import com.midokura.midolman.mgmt.rest_api.jaxrs.JsonJaxbSerializer;
import com.midokura.midolman.state.*;
import com.midokura.midolman.util.Serializer;

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

    @Override
    public BridgeLinkDao getBridgeLinkDao() throws StateAccessException {
        return new BridgeLinkDaoAdapter(getBridgeZkDao(), getBridgeOpService());
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
                getBridgeSerializer());
    }

    private BridgeOpBuilder getBridgeOpBuilder() throws StateAccessException {
        return new BridgeOpBuilder(getBridgeZkManager(), getPathBuilder(),
                getBridgeSerializer());
    }

    private BridgeOpService getBridgeOpService() throws StateAccessException {
        return new BridgeOpService(getBridgeOpBuilder(), getPortOpService(),
                getBridgeZkDao());
    }

    private BridgeSerializer getBridgeSerializer() {
        Serializer<BridgeMgmtConfig> serializer = new JsonJaxbSerializer<BridgeMgmtConfig>();
        Serializer<BridgeNameMgmtConfig> nameSerializer = new JsonJaxbSerializer<BridgeNameMgmtConfig>();
        Serializer<PeerRouterConfig> peerSerializer = new JsonJaxbSerializer<PeerRouterConfig>();
        return new BridgeSerializer(serializer, nameSerializer, peerSerializer);
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
                getChainSerializer());
    }

    private ChainZkManager getChainZkManager() throws StateAccessException {
        return new ChainZkManager(getDirectory(), getRootPath());
    }

    private ChainOpBuilder getChainOpBuilder() throws StateAccessException {
        return new ChainOpBuilder(getChainZkManager(), getPathBuilder(),
                getChainSerializer());
    }

    private ChainOpService getChainOpService() throws StateAccessException {
        return new ChainOpService(getChainOpBuilder(), getChainZkDao());
    }

    private ChainSerializer getChainSerializer() {
        Serializer<ChainMgmtConfig> serializer = new JsonJaxbSerializer<ChainMgmtConfig>();
        Serializer<ChainNameMgmtConfig> nameSerializer = new JsonJaxbSerializer<ChainNameMgmtConfig>();
        return new ChainSerializer(serializer, nameSerializer);
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
                getPortSerializer());
    }

    private PortOpService getPortOpService() throws StateAccessException {
        return new PortOpService(getPortOpBuilder(), getPortZkDao());
    }

    private PortSerializer getPortSerializer() {
        Serializer<PortMgmtConfig> serializer = new JsonJaxbSerializer<PortMgmtConfig>();
        return new PortSerializer(serializer);
    }

    private PortZkDao getPortZkDao() throws StateAccessException {
        return new PortZkDao(getPortZkManager(), getPathBuilder(),
                getPortSerializer());
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
                getChainDao(), getPortDao(), getRouteDao());
    }

    private RouterZkManager getRouterZkManager() throws StateAccessException {
        return new RouterZkManager(getDirectory(), getRootPath());
    }

    private RouterZkDao getRouterZkDao() throws StateAccessException {
        return new RouterZkDao(getRouterZkManager(), getPathBuilder(),
                getRouterSerializer());
    }

    private RouterOpBuilder getRouterOpBuilder() throws StateAccessException {
        return new RouterOpBuilder(getRouterZkManager(), getPathBuilder(),
                getRouterSerializer());
    }

    private RouterOpService getRouterOpService() throws StateAccessException {
        return new RouterOpService(getRouterOpBuilder(), getPortOpService(),
                getBridgeOpBuilder(), getRouterZkDao());
    }

    private RouterSerializer getRouterSerializer() {
        Serializer<RouterMgmtConfig> serializer = new JsonJaxbSerializer<RouterMgmtConfig>();
        Serializer<RouterNameMgmtConfig> nameSerializer = new JsonJaxbSerializer<RouterNameMgmtConfig>();
        Serializer<PeerRouterConfig> peerSerializer = new JsonJaxbSerializer<PeerRouterConfig>();
        return new RouterSerializer(serializer, nameSerializer, peerSerializer);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getRouterLinkDao()
     */
    @Override
    public RouterLinkDao getRouterLinkDao() throws StateAccessException {
        return new RouterLinkDaoAdapter(getRouterZkDao(), getRouterOpService());
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
                getBridgeDao(), getRouterDao(), getChainDao());
    }

    private TenantZkDao getTenantZkDao() throws StateAccessException {
        return new TenantZkDao(getZkDao(), getPathBuilder());
    }

    private TenantOpBuilder getTenantOpBuilder() throws StateAccessException {
        return new TenantOpBuilder(getZkDao(), getPathBuilder());
    }

    private TenantOpService getTenantOpService() throws StateAccessException {
        return new TenantOpService(getTenantOpBuilder(), getBridgeOpService(),
                getRouterOpService(), getTenantZkDao(), getChainOpService());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.DaoFactory#getVifDao()
     */
    @Override
    public VifDao getVifDao() throws StateAccessException {
        return new VifDaoAdapter(getVifZkDao(), getVifOpService());
    }

    private VifZkDao getVifZkDao() throws StateAccessException {
        return new VifZkDao(getZkDao(), getPathBuilder(), getVifSerializer());
    }

    private VifOpBuilder getVifOpBuilder() throws StateAccessException {
        return new VifOpBuilder(getZkDao(), getPathBuilder(),
                getVifSerializer());
    }

    private VifOpService getVifOpService() throws StateAccessException {
        return new VifOpService(getVifOpBuilder(), getPortOpService(),
                getVifZkDao());
    }

    private VifSerializer getVifSerializer() {
        Serializer<VifConfig> serializer = new JsonJaxbSerializer<VifConfig>();
        return new VifSerializer(serializer);
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

    private VpnZkManager getVpnZkManager() throws StateAccessException {
        return new VpnZkManager(getDirectory(), this.rootPath);
    }

    private ZkManager getZkDao() throws StateAccessException {
        return new ZkManager(getDirectory(), getRootPath());
    }
}
