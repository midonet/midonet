/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.host.guice;

import com.google.inject.Singleton;

import org.midonet.midolman.host.services.HostAgentService;
import org.midonet.midolman.services.SelectLoopService;

/**
 * Module to configure dependencies for the host agent.
 */
public class HostAgentModule extends HostModule {

    @Override
    protected void configure() {
        super.configure();

        bind(SelectLoopService.class).in(Singleton.class);
        expose(SelectLoopService.class);

        bind(HostAgentService.class).asEagerSingleton();
        expose(HostAgentService.class);
    }

}
