/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.data;

import com.midokura.midolman.mgmt.config.AppConfig;
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
import com.midokura.midolman.state.StateAccessException;

/**
 * This is a DaoFactory that will create and keep a reference to a static
 * MockDaoFactory in order to allow an integration test access to a Directory
 * implementation that is shared between the test and the Midolman-mgmt Jersey
 * application.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date 1/31/12
 */
public class StaticMockDaoFactory implements DaoFactory {

    private MockDaoFactory factoryInstance;

    public StaticMockDaoFactory(AppConfig config)
        throws DaoInitializationException {
        factoryInstance = _initializeWrappedInstance(config);
    }

    private static MockDaoFactory _factoryInstance;

    private static synchronized MockDaoFactory _initializeWrappedInstance(
        AppConfig config)
        throws DaoInitializationException {
        if (_factoryInstance == null) {
            _factoryInstance = new MockDaoFactory(config);
        }

        return _factoryInstance;
    }

    public static MockDaoFactory getFactoryInstance() {
        return _factoryInstance;
    }

    public static void clearFactoryInstance() {
        _factoryInstance = null;
    }

    @Override
    public ApplicationDao getApplicationDao()
        throws StateAccessException {
        return factoryInstance.getApplicationDao();
    }

    @Override
    public AdRouteDao getAdRouteDao() throws StateAccessException {
        return factoryInstance.getAdRouteDao();
    }

    @Override
    public BgpDao getBgpDao() throws StateAccessException {
        return factoryInstance.getBgpDao();
    }

    @Override
    public BridgeDao getBridgeDao() throws StateAccessException {
        return factoryInstance.getBridgeDao();
    }

    @Override
    public ChainDao getChainDao() throws StateAccessException {
        return factoryInstance.getChainDao();
    }

    @Override
    public HostDao getHostDao() throws StateAccessException {
        return factoryInstance.getHostDao();
    }

    @Override
    public PortDao getPortDao() throws StateAccessException {
        return factoryInstance.getPortDao();
    }

    @Override
    public RouteDao getRouteDao() throws StateAccessException {
        return factoryInstance.getRouteDao();
    }

    @Override
    public RouterDao getRouterDao() throws StateAccessException {
        return factoryInstance.getRouterDao();
    }

    @Override
    public RuleDao getRuleDao() throws StateAccessException {
        return factoryInstance.getRuleDao();
    }

    @Override
    public TenantDao getTenantDao() throws StateAccessException {
        return factoryInstance.getTenantDao();
    }

    @Override
    public VpnDao getVpnDao() throws StateAccessException {
        return factoryInstance.getVpnDao();
    }

    @Override
    public DhcpDao getDhcpDao() throws StateAccessException {
        return factoryInstance.getDhcpDao();
    }

    @Override
    public PortGroupDao getPortGroupDao() throws StateAccessException {
        return factoryInstance.getPortGroupDao();
    }

    public MetricDao getMetricDao() throws StateAccessException {
        return factoryInstance.getMetricDao();
    }
}
