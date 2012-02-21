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
import com.midokura.midolman.state.Directory;

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

    /**
     * This method is called by the Guice library to infer bindings for the
     * objects that are managed by guice.
     */
    @Override
    protected void configure() {
        bind(InterfaceScanner.class).to(DefaultInterfaceScanner.class);
        bind(InterfaceDataUpdater.class).to(DefaultInterfaceDataUpdater.class);
    }

    @Provides
    HostZkManager buildHostManager(Directory directory,
                                   HostAgentConfiguration configuration) {
        return new HostZkManager(directory,
                                 configuration.getZooKeeperBasePath());
    }
}
