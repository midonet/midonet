/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice;

import com.google.inject.PrivateModule;
import com.google.inject.Singleton;
import com.midokura.cache.Cache;
import com.midokura.midolman.DatapathController;
import com.midokura.midolman.FlowController;
import com.midokura.midolman.SimulationController;
import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.monitoring.MonitoringActor;
import com.midokura.midolman.monitoring.metrics.vrn.VifMetrics;
import com.midokura.midolman.routingprotocols.RoutingManagerActor;
import com.midokura.midolman.services.HostIdProviderService;
import com.midokura.midolman.services.MidolmanActorsService;
import com.midokura.midolman.topology.*;
import com.midokura.netlink.protos.OvsDatapathConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This Guice module will bind an instance of {@link MidolmanActorsService} so
 * that it can be retrieved by the client class and booted up at the system
 * initialization time.
 */
public class MidolmanActorsModule extends PrivateModule {
    private static final Logger log = LoggerFactory
            .getLogger(MidolmanActorsModule.class);

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(MidolmanConfig.class);
        requireBinding(Cache.class);
        requireBinding(OvsDatapathConnection.class);
        requireBinding(HostIdProviderService.class);

        bindMidolmanActorsService();
        expose(MidolmanActorsService.class);

        bind(VifMetrics.class).in(Singleton.class);

        bind(VirtualTopologyActor.class).in(Singleton.class);
        bind(VirtualToPhysicalMapper.class).in(Singleton.class);
        bind(DatapathController.class).in(Singleton.class);
        bind(FlowController.class).in(Singleton.class);
        bind(SimulationController.class).in(Singleton.class);
        bind(MonitoringActor.class).in(Singleton.class);
        //bind(InterfaceScanner.class).to(DefaultInterfaceScanner.class);
        bind(HostManager.class);
        bind(TunnelZoneManager.class);
        bind(PortSetManager.class);
        bind(RoutingManagerActor.class);
    }

    protected void bindMidolmanActorsService() {
        bind(MidolmanActorsService.class).in(Singleton.class);
    }
}
