/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.host.guice;

import com.midokura.midolman.host.services.HostAgentService;

/**
 * Module to configure dependencies for the host agent.
 */
public class HostAgentModule extends HostModule {

    @Override
    protected void configure() {
        super.configure();

        bind(HostAgentService.class).asEagerSingleton();
        expose(HostAgentService.class);
    }

}
