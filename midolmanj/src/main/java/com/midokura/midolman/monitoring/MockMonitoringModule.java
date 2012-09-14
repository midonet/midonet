/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring;

import com.google.inject.PrivateModule;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.midokura.config.ConfigProvider;
import com.midokura.midolman.guice.config.ConfigFromFileProvider;
import com.midokura.midolman.host.HostInterfaceWatcher;
import com.midokura.midolman.host.commands.executors.CommandInterpreter;
import com.midokura.midolman.host.commands.executors.HostCommandWatcher;
import com.midokura.midolman.host.config.HostConfig;
import com.midokura.midolman.host.guice.HostConfigProvider;
import com.midokura.midolman.host.services.HostService;
import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.monitoring.config.MonitoringConfiguration;
import com.midokura.midolman.monitoring.metrics.VMMetricsCollection;
import com.midokura.midolman.monitoring.metrics.ZookeeperMetricsCollection;
import com.midokura.midolman.monitoring.store.MockCassandraStore;
import com.midokura.midolman.monitoring.store.Store;
import com.midokura.midolman.services.HostIdProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Date: 4/25/12
 */
public class MockMonitoringModule extends PrivateModule {

    private final static Logger log =
        LoggerFactory.getLogger(MockMonitoringModule.class);

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        bind(HostCommandWatcher.class);
        bind(CommandInterpreter.class).in(Scopes.SINGLETON);
        bind(HostInterfaceWatcher.class);

        bind(HostConfig.class)
                         .toProvider(HostConfigProvider.class)
                         .asEagerSingleton();

        bind(HostZkManager.class);
        bind(HostIdProviderService.class)
                     .to(HostService.class)
                     .in(Singleton.class);

        requireBinding(HostIdProviderService.class);
        requireBinding(ConfigProvider.class);
        requireBinding(MonitoringConfiguration.class);
        bind(Store.class).to(MockCassandraStore.class);

        bind(VMMetricsCollection.class);
        bind(ZookeeperMetricsCollection.class);
        bind(HostKeyService.class);

        bind(MonitoringAgent.class);
        expose(MonitoringAgent.class);

    }
}
