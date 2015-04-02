/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.cluster;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import scala.concurrent.duration.Duration;

import akka.actor.OneForOneStrategy;
import akka.actor.SupervisorStrategy;
import akka.actor.SupervisorStrategy.Directive;
import akka.japi.Function;
import com.google.inject.BindingAnnotation;
import com.google.inject.Exposed;
import com.google.inject.PrivateModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.DatapathController;
import org.midonet.midolman.NetlinkCallbackDispatcher;
import org.midonet.midolman.PacketsEntryPoint;
import org.midonet.midolman.SupervisorActor;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.io.DatapathConnectionPool;
import org.midonet.midolman.io.UpcallDatapathConnectionManager;
import org.midonet.midolman.l4lb.HealthMonitor;
import org.midonet.midolman.routingprotocols.RoutingManagerActor;
import org.midonet.midolman.services.HostIdProviderService;
import org.midonet.midolman.services.MidolmanActorsService;
import org.midonet.midolman.state.FlowStateStorageFactory;
import org.midonet.midolman.topology.VirtualToPhysicalMapper;
import org.midonet.midolman.topology.VirtualTopologyActor;
import org.midonet.netlink.NetlinkChannelFactory;
import org.midonet.util.concurrent.NanoClock;
import org.midonet.util.concurrent.NanoClock$;
import org.midonet.util.eventloop.SelectLoop;
import org.midonet.util.eventloop.SimpleSelectLoop;

import static akka.actor.SupervisorStrategy.stop;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * This Guice module will bind an instance of {@link MidolmanActorsService} so
 * that it can be retrieved by the client class and booted up at the system
 * initialization time.
 */
public class MidolmanActorsModule extends PrivateModule {
    public static final String CRASH_STRATEGY_NAME = "crash";
    public static final String RESUME_STRATEGY_NAME = "resume";

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
        requireBinding(DatapathConnectionPool.class);
        requireBinding(HostIdProviderService.class);
        requireBinding(UpcallDatapathConnectionManager.class);
        requireBinding(FlowStateStorageFactory.class);
        requireBinding(NetlinkChannelFactory.class);

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
        bind(PacketsEntryPoint.class);
        bind(NetlinkCallbackDispatcher.class);
        bind(RoutingManagerActor.class);
        bind(HealthMonitor.class);
    }

    protected void bindMidolmanActorsService() {
        bind(NanoClock.class).toInstance(NanoClock$.MODULE$.DEFAULT());
        bind(MidolmanActorsService.class).in(Singleton.class);
    }

    @Provides @Exposed
    public SupervisorStrategy getSupervisorActorStrategy() {
        return getCrashStrategy();
    }

    @Provides @Exposed @Singleton @ZEBRA_SERVER_LOOP
    public SelectLoop provideSelectLoop() {
        try {
            return new SimpleSelectLoop();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Provides @Exposed @CRASH_STRATEGY
    public SupervisorStrategy getCrashStrategy() {
        return new OneForOneStrategy(-1, Duration.Inf(),
                new Function<Throwable, Directive>() {
                    @Override
                    public Directive apply(Throwable t) {
                        log.warn("Actor crashed, aborting", t);
                        System.exit(-1);
                        return stop();
                    }
                });
    }
}
