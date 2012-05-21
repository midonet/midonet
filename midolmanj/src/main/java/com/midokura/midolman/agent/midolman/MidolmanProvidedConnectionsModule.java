/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.midolman;

import javax.inject.Named;

import com.google.inject.Provides;

import com.midokura.config.ConfigProvider;
import com.midokura.midolman.agent.config.HostAgentConfiguration;
import com.midokura.midolman.agent.modules.AbstractAgentModule;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkConnection;

/**
 * Concrete Guice module implementation that is used when you want to launch the
 * NodeAgent from inside the Midolman process.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/9/12
 */
public class MidolmanProvidedConnectionsModule extends AbstractAgentModule {

    private ZkConnection zkConnection;
    private OpenvSwitchDatabaseConnection ovsdbConnection;
    private ConfigProvider config;

    public MidolmanProvidedConnectionsModule(ConfigProvider config,
                                             ZkConnection zkConnection,
                                             OpenvSwitchDatabaseConnection ovsdbConnection) {
        this.zkConnection = zkConnection;
        this.ovsdbConnection = ovsdbConnection;
        this.config = config;
    }

    /**
     * This method is called by the Guice library to infer bindings for the
     * objects that are managed by guice.
     */
    @Override
    protected void configure() {
        super.configure();

        bind(ConfigProvider.class).toInstance(config);
    }

    @Provides
    @Named()
    @Override
    public HostAgentConfiguration buildHostAgentConfiguration(ConfigProvider config) {
        return config.getConfig(MidolmanBasedHostAgentConfiguration.class);
    }

    Directory buildRootDirectory() {
        return zkConnection.getRootDirectory();
    }

    @Provides
    OpenvSwitchDatabaseConnection buildOvsDatabaseConnection() {
        return ovsdbConnection;
    }
}
