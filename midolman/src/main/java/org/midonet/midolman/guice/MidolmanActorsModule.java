/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import scala.concurrent.duration.Duration;

import akka.actor.ActorInitializationException;
import akka.actor.ActorKilledException;
import akka.actor.OneForOneStrategy;
import akka.actor.SupervisorStrategy;
import akka.actor.SupervisorStrategy.Directive;
import akka.japi.Function;
import com.google.inject.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cache.Cache;
import org.midonet.midolman.*;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.host.config.HostConfig;
import org.midonet.midolman.io.DatapathConnectionPool;
import org.midonet.midolman.io.UpcallDatapathConnectionManager;
import org.midonet.midolman.l4lb.HealthMonitor;
import org.midonet.midolman.monitoring.MonitoringActor;
import org.midonet.midolman.routingprotocols.RoutingManagerActor;
import org.midonet.midolman.services.HostIdProviderService;
import org.midonet.midolman.services.MidolmanActorsService;
import org.midonet.midolman.topology.*;
import org.midonet.util.eventloop.SelectLoop;
import org.midonet.util.eventloop.SimpleSelectLoop;

import static org.midonet.midolman.guice.CacheModule.NAT_CACHE;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import static akka.actor.SupervisorStrategy.resume;
import static akka.actor.SupervisorStrategy.stop;
import static akka.actor.SupervisorStrategy.escalate;

/**
 * This Guice module will bind an instance of {@link MidolmanActorsService} so
 * that it can be retrieved by the client class and booted up at the system
 * initialization time.
 */
public class MidolmanActorsModule extends PrivateModule {
    public static final String CRASH_STRATEGY_NAME = "crash";
    public static final String RESUME_STRATEGY_NAME = "resume";

    @BindingAnnotation @Target({FIELD, METHOD}) @Retention(RUNTIME)
    public @interface RESUME_STRATEGY {}
    @BindingAnnotation @Target({FIELD, METHOD}) @Retention(RUNTIME)
    public @interface CRASH_STRATEGY {}

    @BindingAnnotation @Target({FIELD, METHOD}) @Retention(RUNTIME)
    public @interface ZEBRA_SERVER_LOOP {}

    private static final Logger log = LoggerFactory
            .getLogger(MidolmanActorsModule.class);

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(MidolmanConfig.class);
        requireBinding(Key.get(Cache.class, NAT_CACHE.class));
        requireBinding(DatapathConnectionPool.class);
        requireBinding(HostIdProviderService.class);
        requireBinding(HostConfig.class);
        requireBinding(UpcallDatapathConnectionManager.class);

        bindMidolmanActorsService();
        expose(MidolmanActorsService.class);

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
        bind(PacketsEntryPoint.class);
        bind(NetlinkCallbackDispatcher.class);
        bind(MonitoringActor.class);
        //bind(InterfaceScanner.class).to(DefaultInterfaceScanner.class);
        bind(RoutingManagerActor.class);
        bind(HealthMonitor.class);
    }

    protected void bindMidolmanActorsService() {
        bind(MidolmanActorsService.class).in(Singleton.class);
    }

    @Provides @Exposed
    public SupervisorStrategy getSupervisorActorStrategy(MidolmanConfig config) {
        String strategy = config.getMidolmanTopLevelActorsSupervisor();
        switch (strategy) {
            case CRASH_STRATEGY_NAME:
                return getCrashStrategy();
            case RESUME_STRATEGY_NAME:
                return getResumeStrategy();
            default:
                log.warn("Unknown supervisor strategy [{}], " +
                         "falling back to resume strategy", strategy);
                return getResumeStrategy();
        }
    }

    @Provides @Exposed @Singleton @ZEBRA_SERVER_LOOP
    public SelectLoop provideSelectLoop() {
        try {
            return new SimpleSelectLoop();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Provides @Exposed @RESUME_STRATEGY
    public SupervisorStrategy getResumeStrategy() {
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

    @Provides @Exposed @CRASH_STRATEGY
    public SupervisorStrategy getCrashStrategy() {
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
