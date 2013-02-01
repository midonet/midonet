/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.host.guice;

import org.midonet.midolman.host.services.HostAgentService;

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
