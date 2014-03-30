/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package org.midonet.midolman.monitoring;

import com.google.inject.PrivateModule;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.host.commands.executors.CommandInterpreter;
import org.midonet.midolman.host.commands.executors.HostCommandWatcher;
import org.midonet.midolman.host.config.HostConfig;
import org.midonet.midolman.host.guice.HostConfigProvider;
import org.midonet.midolman.host.services.HostService;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.monitoring.config.MonitoringConfiguration;
import org.midonet.midolman.monitoring.metrics.VMMetricsCollection;
import org.midonet.midolman.monitoring.metrics.ZookeeperMetricsCollection;
import org.midonet.midolman.monitoring.store.MockStore;
import org.midonet.midolman.monitoring.store.Store;
import org.midonet.midolman.services.HostIdProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockMonitoringModule extends PrivateModule {

    private final static Logger log =
        LoggerFactory.getLogger(MockMonitoringModule.class);

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        bind(HostCommandWatcher.class);
        bind(CommandInterpreter.class).in(Scopes.SINGLETON);

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
        bind(Store.class).to(MockStore.class);

        bind(VMMetricsCollection.class);
        bind(ZookeeperMetricsCollection.class);
        bind(HostKeyService.class);

        bind(MonitoringAgent.class);
        expose(MonitoringAgent.class);

    }
}
