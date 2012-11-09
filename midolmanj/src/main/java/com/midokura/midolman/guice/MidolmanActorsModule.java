/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice;

import akka.actor.ActorInitializationException;
import akka.actor.ActorKilledException;
import akka.actor.OneForOneStrategy;
import akka.actor.SupervisorStrategy;
import akka.actor.SupervisorStrategy.Directive;
import akka.japi.Function;
import akka.util.Duration;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.cache.Cache;
import com.midokura.midolman.DatapathController;
import com.midokura.midolman.FlowController;
import com.midokura.midolman.SimulationController;
import com.midokura.midolman.SupervisorActor;
import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.monitoring.MonitoringActor;
import com.midokura.midolman.monitoring.metrics.vrn.VifMetrics;
import com.midokura.midolman.routingprotocols.RoutingManagerActor;
import com.midokura.midolman.services.HostIdProviderService;
import com.midokura.midolman.services.MidolmanActorsService;
import com.midokura.midolman.topology.*;
import com.midokura.netlink.protos.OvsDatapathConnection;

import static akka.actor.SupervisorStrategy.resume;
import static akka.actor.SupervisorStrategy.stop;
import static akka.actor.SupervisorStrategy.escalate;

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

        bind(SupervisorStrategy.class)
                .toProvider(CrashStrategyProvider.class)
                .in(Singleton.class);

        expose(SupervisorStrategy.class);

        requireBinding(MidolmanConfig.class);
        requireBinding(Cache.class);
        requireBinding(OvsDatapathConnection.class);
        requireBinding(HostIdProviderService.class);

        bindMidolmanActorsService();
        expose(MidolmanActorsService.class);

        bind(VifMetrics.class).in(Singleton.class);

        /* NOTE(guillermo) In midolman's architecture these actors are all
         * singletons. However this constraint is enforced by
         * MidolmanActorsService, which launches them at the top level with
         * a well-known name.
         *
         * Here we do allow the creation of multiple instances because,
         * while there will only be one actor of each type, akka expects that
         * relaunching an actor be done with a fresh instance. If we asked
         * akka to restart an actor and we gave it the old instance, bad things
         * would happen (the behaviour is not defined but akka v2.0.3 will
         * start the actor with a null context). */
        bind(SupervisorActor.class);
        bind(VirtualTopologyActor.class);
        bind(VirtualToPhysicalMapper.class);
        bind(DatapathController.class);
        bind(FlowController.class);
        bind(SimulationController.class);
        bind(MonitoringActor.class);
        //bind(InterfaceScanner.class).to(DefaultInterfaceScanner.class);
        bind(HostManager.class);
        bind(TunnelZoneManager.class);
        bind(PortSetManager.class);
        bind(RoutingManagerActor.class);
    }

    protected void bindMidolmanActorsService() {
        bind(MidolmanActorsService.class).in(Singleton.class);
    }

    public static class ResumeStrategyProvider
            implements Provider<SupervisorStrategy> {

        @Override
        public SupervisorStrategy get() {
            return new OneForOneStrategy(-1, Duration.Inf(),
                new Function<Throwable, Directive>() {
                    @Override
                    public Directive apply(Throwable t) {
                        if (t instanceof ActorKilledException)
                            return escalate();
                        else if (t instanceof ActorInitializationException)
                            return stop();
                        else
                            return resume();
                    }
                });
        }
    }

    public static class CrashStrategyProvider
            implements Provider<SupervisorStrategy> {

        @Override
        public SupervisorStrategy get() {
            return new OneForOneStrategy(-1, Duration.Inf(),
                    new Function<Throwable, Directive>() {
                        @Override
                        public Directive apply(Throwable t) {
                            log.warn("Actor crashed, aborting: {}", t);
                            System.exit(-1);
                            return stop();
                        }
                    });
        }
    }
}
