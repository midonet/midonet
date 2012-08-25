/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.guice.data.zookeeper;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.midokura.midolman.config.ZookeeperConfig;
import com.midokura.midolman.mgmt.data.dao.*;
import com.midokura.midolman.mgmt.data.zookeeper.dao.*;
import com.midokura.midolman.state.PathBuilder;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathService;
import com.midokura.midolman.mgmt.jaxrs.JsonJaxbSerializer;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkConfigSerializer;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midolman.util.Serializer;

/**
 * ZK DAO module
 */
public class ZookeeperDaoModule extends AbstractModule {

    @Override
    protected void configure() {

        requireBinding(Directory.class);
        requireBinding(ZookeeperConfig.class);

        // PathService
        bind(PathService.class).asEagerSingleton();

        // Serializer
        Serializer serializer = new JsonJaxbSerializer();
        bind(Serializer.class).toInstance(serializer);

        // ZkConfigSerializer
        bind(ZkConfigSerializer.class).toInstance(
                new ZkConfigSerializer(serializer));

        // Ad Route
        bind(AdRouteDao.class).to(AdRouteDaoImpl.class).asEagerSingleton();

        // Application
        bind(ApplicationDao.class).to(
                ApplicationDaoImpl.class).asEagerSingleton();

        // BGP
        bind(BgpDao.class).to(BgpDaoImpl.class).asEagerSingleton();

        // VPN
        bind(VpnDao.class).to(VpnDaoImpl.class).asEagerSingleton();

        // Port
        bind(PortDao.class).to(PortDaoImpl.class).asEagerSingleton();

        // Bridge
        bind(BridgeDao.class).to(BridgeZkDaoImpl.class).asEagerSingleton();
        bind(BridgeZkDao.class).to(BridgeZkDaoImpl.class).asEagerSingleton();

        // Rule
        bind(RuleDao.class).to(RuleDaoImpl.class).asEagerSingleton();

        // Chain
        bind(ChainDao.class).to(ChainZkDaoImpl.class).asEagerSingleton();
        bind(ChainZkDao.class).to(ChainZkDaoImpl.class).asEagerSingleton();

        // DHCP
        bind(DhcpDao.class).to(DhcpDaoImpl.class).asEagerSingleton();

        // Host
        bind(HostDao.class).to(HostDaoImpl.class).asEagerSingleton();

        // Port Group
        bind(PortGroupDao.class).to(
                PortGroupZkDaoImpl.class).asEagerSingleton();
        bind(PortGroupZkDao.class).to(
                PortGroupZkDaoImpl.class).asEagerSingleton();

        // Route
        bind(RouteDao.class).to(RouteDaoImpl.class).asEagerSingleton();

        // Router
        bind(RouterDao.class).to(RouterZkDaoImpl.class).asEagerSingleton();
        bind(RouterZkDao.class).to(RouterZkDaoImpl.class).asEagerSingleton();

    }

    @Inject
    @Provides
    PathBuilder providePathBuilder(ZookeeperConfig config) {
        return new PathBuilder(config.getMidolmanRootKey());
    }

    @Inject
    @Provides
    ZkManager provideZkManager(Directory directory, ZookeeperConfig config) {
        return new ZkManager(directory, config.getMidolmanRootKey());
    }

}
