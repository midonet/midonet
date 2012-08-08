/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.guice;

import com.google.inject.*;
import com.midokura.midolman.host.commands.executors.CommandInterpreter;
import com.midokura.midolman.host.scanner.DefaultInterfaceScanner;
import com.midokura.midolman.host.scanner.InterfaceScanner;
import com.midokura.midolman.host.updater.DefaultInterfaceDataUpdater;
import com.midokura.midolman.host.updater.InterfaceDataUpdater;
import com.midokura.midolman.config.HostAgentConfig;

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
