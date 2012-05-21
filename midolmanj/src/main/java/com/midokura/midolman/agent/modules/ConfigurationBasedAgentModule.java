/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.modules;

import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.config.ConfigProvider;
import com.midokura.midolman.agent.config.HostAgentConfiguration;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkConnection;

/**
 * Concrete Guice module configurator that is used when you launch the NodeAgent
 * in standalone mode.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/9/12
 */
public class ConfigurationBasedAgentModule extends AbstractAgentModule {

    private final static Logger log =
        LoggerFactory.getLogger(ConfigurationBasedAgentModule.class);

    private String configFilePath;

    public ConfigurationBasedAgentModule(String configFilePath) {
        this.configFilePath = configFilePath;
    }

    @Override
    @Provides
    @Singleton
    public HostAgentConfiguration buildHostAgentConfiguration(ConfigProvider config) {
        return config.getConfig(HostAgentConfiguration.class);
    }

    @Provides
    @Singleton
    public ConfigProvider buildConfigProvider() throws ConfigurationException {
        HierarchicalINIConfiguration config =
            new HierarchicalINIConfiguration(configFilePath);

        return ConfigProvider.providerForIniConfig(config);
    }

    @Provides
    @Singleton
    Directory builtRootDirectory(HostAgentConfiguration config)
        throws Exception {

        final ZkConnection zkConnection = new ZkConnection(
            config.getZooKeeperHosts(),
            config.getZooKeeperSessionTimeout(),
            null, null);

        log.debug("Opening a ZkConnection");
        zkConnection.open();
        log.debug("Opening of the ZkConnection was successful");

        log.info("Adding shutdownHook");
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                log.warn("In shutdown hook: disconnecting ZK.");
                zkConnection.close();
                log.warn("Exiting. BYE!");
            }
        });

        return zkConnection.getRootDirectory();
    }

    @Provides
    @Singleton
    OpenvSwitchDatabaseConnection buildOvsDatabaseConnection(
        HostAgentConfiguration config) {
        return
            new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                                                  config.getOpenvSwitchIpAddr(),
                                                  config.getOpenvSwitchTcpPort());
    }
}
