/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice;

import com.google.inject.PrivateModule;
import com.google.inject.Singleton;

import com.midokura.midolman.DatapathController;
import com.midokura.midolman.FlowController;
import com.midokura.midolman.SimulationController;
import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.services.MidolmanActorsService;
import com.midokura.midolman.topology.VirtualToPhysicalMapper;
import com.midokura.midolman.topology.VirtualTopologyActor;
import com.midokura.netlink.protos.OvsDatapathConnection;
import com.midokura.sdn.flows.FlowManager;

/**
 * This Guice module will bind an instance of {@link MidolmanActorsService} so
 * that it can be retrieved by the client class and booted up at the system
 * initialization time.
 */
public class MidolmanActorsModule extends PrivateModule {
    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(MidolmanConfig.class);
        requireBinding(OvsDatapathConnection.class);

        bindMidolmanActorsService();
        expose(MidolmanActorsService.class);

        bind(VirtualTopologyActor.class).in(Singleton.class);
        bind(VirtualToPhysicalMapper.class).in(Singleton.class);
        bind(DatapathController.class).in(Singleton.class);
        bind(FlowController.class).in(Singleton.class);
        bind(SimulationController.class).in(Singleton.class);

        bind(FlowManager.class);
    }

    protected void bindMidolmanActorsService() {
        bind(MidolmanActorsService.class).in(Singleton.class);
    }
}
