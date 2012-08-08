/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.guice;

import com.google.inject.*;
import com.midokura.midolman.agent.commands.executors.CommandInterpreter;
import com.midokura.midolman.agent.scanner.DefaultInterfaceScanner;
import com.midokura.midolman.agent.scanner.InterfaceScanner;
import com.midokura.midolman.agent.state.HostZkManager;
import com.midokura.midolman.agent.updater.DefaultInterfaceDataUpdater;
import com.midokura.midolman.agent.updater.InterfaceDataUpdater;
import com.midokura.midolman.config.HostAgentConfig;
import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.state.Directory;

/**
 * Module to configure dependencies for the host agent.
 */
public class HostAgentModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(InterfaceScanner.class).to(DefaultInterfaceScanner.class);
        bind(InterfaceDataUpdater.class).to(DefaultInterfaceDataUpdater.class);
        bind(CommandInterpreter.class).in(Scopes.SINGLETON);
        bind(HostAgentConfig.class)
                .toProvider(HostAgentConfigProvider.class)
                .asEagerSingleton();
    }

}
