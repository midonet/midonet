/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.services;

import java.util.concurrent.TimeUnit;

import akka.actor.Actor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActorFactory;
import akka.dispatch.Await;
import akka.dispatch.Future;
import akka.pattern.Patterns;
import akka.util.Duration;
import akka.util.Timeout;
import com.google.common.util.concurrent.AbstractService;
import com.google.inject.Inject;
import com.google.inject.Injector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.DatapathController;
import com.midokura.midolman.FlowController;
import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.guice.ComponentInjectorHolder;
import com.midokura.midolman.topology.VirtualToPhysicalMapper;
import com.midokura.midolman.topology.VirtualTopologyActor;


import static akka.pattern.Patterns.gracefulStop;

/**
 * Midolman actors coordinator internal service.
 * <p/>
 * It can start the actor system, spawn the initial top level actors and kill
 * them when it's time to shutdown.
 */
public class MidolmanActorsService extends AbstractService {

    private static final Logger log = LoggerFactory
        .getLogger(MidolmanActorsService.class);

    @Inject
    MidolmanConfig config;

    @Inject
    Injector injector;

    protected ActorSystem actorSystem;

    ActorRef virtualTopologyActor;
    ActorRef datapathControllerActor;
    ActorRef virtualToPhysicalActor;
    ActorRef flowControllerActor;

    @Override
    protected void doStart() {
        ComponentInjectorHolder.setInjector(injector);

        log.info("Booting up actors service");

        log.debug("Creating actors system.");
        actorSystem = ActorSystem.create("midolmanActors");

        virtualTopologyActor =
            startActor(
                getGuiceAwareFactory(VirtualTopologyActor.class),
                VirtualTopologyActor.Name());

        virtualToPhysicalActor =
            startActor(
                getGuiceAwareFactory(VirtualToPhysicalMapper.class),
                VirtualToPhysicalMapper.Name());

        datapathControllerActor =
            startActor(
                getGuiceAwareFactory(DatapathController.class),
                DatapathController.Name());

        flowControllerActor =
            startActor(getGuiceAwareFactory(FlowController.class),
                       FlowController.Name());

        notifyStarted();
        log.info("Actors system started");
    }

    @Override
    protected void doStop() {
        try {
            stopActor(datapathControllerActor);
            stopActor(virtualTopologyActor);
            stopActor(virtualToPhysicalActor);
            stopActor(flowControllerActor);

            log.debug("Stopping the actor system");
            actorSystem.shutdown();
            notifyStopped();
        } catch (Exception e) {
            log.error("Exception", e);
            notifyFailed(e);
        }
    }

    private Props getGuiceAwareFactory(Class<? extends Actor> actorClass) {
        return new Props(new GuiceActorFactory(injector, actorClass));
    }

    private void stopActor(ActorRef actorRef) {
        log.debug("Stopping actor: {}", actorRef.toString());
        try {
            Future<Boolean> stopFuture =
                gracefulStop(virtualTopologyActor,
                             Duration.create(100, TimeUnit.MILLISECONDS),
                             actorSystem);
            Await.result(stopFuture,
                         Duration.create(150, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            log.debug("Actor {} didn't stop on time. ");
        }
    }

    private ActorRef startActor(Props actorProps, String actorName) {
        ActorRef actorRef = null;

        try {
            log.debug("Starting actor {}", actorName);
            actorRef = makeActorRef(actorProps, actorName);

            log.debug("Started at {}", actorRef);
        } catch (Exception e) {
            log.error("Failed {}", e);
        }

        return actorRef;
    }

    protected ActorRef makeActorRef(Props actorProps, String actorName) {
        return actorSystem.actorOf(actorProps, actorName);
    }

    public void initProcessing() throws Exception {
        log.debug("Sending Initialization message to datapath controller.");

        Timeout timeout = new Timeout(Duration.parse("1 second"));

        Await.result(
            Patterns.ask(datapathControllerActor,
                         DatapathController.getInitialize(), timeout),
            timeout.duration());
    }

    public ActorSystem system() {
        return actorSystem;
    }

    private static class GuiceActorFactory implements UntypedActorFactory {

        Class<? extends Actor> actorClass;
        Injector injector;

        private GuiceActorFactory(Injector injector, Class<? extends Actor> actorClass) {
            this.injector = injector;
            this.actorClass = actorClass;
        }

        @Override
        public Actor create() {
            return injector.getInstance(actorClass);
        }
    }
}
