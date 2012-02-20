/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.modules;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;

import com.midokura.midolman.agent.config.HostAgentConfiguration;
import com.midokura.midolman.agent.midolman.MidolmanProvidedConnectionsModule;
import com.midokura.midolman.agent.scanner.DefaultInterfaceScanner;
import com.midokura.midolman.agent.scanner.InterfaceScanner;
import com.midokura.midolman.agent.state.HostZkManager;
import com.midokura.midolman.agent.updater.DefaultInterfaceDataUpdater;
import com.midokura.midolman.agent.updater.InterfaceDataUpdater;
import com.midokura.midolman.state.ZkConnection;

/**
 * Abstract Guice module implementation that will configure guice with most of the
 * components that the node agent needs.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/8/12
 * @see ConfigurationBasedAgentModule
 * @see MidolmanProvidedConnectionsModule
 */
public abstract class AbstractAgentModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(InterfaceScanner.class).to(DefaultInterfaceScanner.class);
        bind(InterfaceDataUpdater.class).to(DefaultInterfaceDataUpdater.class);
    }

    @Provides
    HostZkManager buildZkConnection(ZkConnection zkConnection,
                                    HostAgentConfiguration configuration) {
        return new HostZkManager(zkConnection.getRootDirectory(),
                                 configuration.getZooKeeperBasePath());
    }
}
