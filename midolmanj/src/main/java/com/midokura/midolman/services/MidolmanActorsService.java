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
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static akka.pattern.Patterns.ask;
import static akka.pattern.Patterns.gracefulStop;

import com.midokura.midolman.DatapathController;
import com.midokura.midolman.FlowController;
import com.midokura.midolman.SimulationController;
import com.midokura.midolman.SupervisorActor;
import com.midokura.midolman.SupervisorActor.StartChild;
import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.guice.ComponentInjectorHolder;
import com.midokura.midolman.monitoring.MonitoringActor;
import com.midokura.midolman.routingprotocols.RoutingManagerActor;
import com.midokura.midolman.topology.VirtualToPhysicalMapper;
import com.midokura.midolman.topology.VirtualTopologyActor;

/**
 * Midolman actors coordinator internal service.
 * <p/>
 * It can start the actor system, spawn the initial top level actors and kill
 * them when it's time to shutdown.
 */
public class MidolmanActorsService extends AbstractService {

    private static final Logger log =
            LoggerFactory.getLogger(MidolmanActorsService.class);

    @Inject
    Injector injector;

    @Inject
    MidolmanConfig config;

    protected ActorSystem actorSystem;

    ActorRef supervisorActor;
    ActorRef virtualTopologyActor;
    ActorRef datapathControllerActor;
    ActorRef virtualToPhysicalActor;
    ActorRef flowControllerActor;
    ActorRef simulationControllerActor;
    ActorRef monitoringActor;
    ActorRef routingManagerActor;

    @Override
    protected void doStart() {
        ComponentInjectorHolder.setInjector(injector);

        log.info("Booting up actors service");

        log.debug("Creating actors system.");
        actorSystem = ActorSystem.create("MidolmanActors",
                ConfigFactory.load().getConfig("midolman"));

        supervisorActor =
                startTopActor(
                        getGuiceAwareFactory(SupervisorActor.class),
                        SupervisorActor.Name());

        virtualTopologyActor =
                startActor(
                        getGuiceAwareFactory(VirtualTopologyActor.class),
                        VirtualTopologyActor.Name());

        virtualToPhysicalActor =
                startActor(
                        getGuiceAwareFactory(VirtualToPhysicalMapper.class).withDispatcher("actors.stash-dispatcher"),
                        VirtualToPhysicalMapper.Name());

        datapathControllerActor =
                startActor(
                        getGuiceAwareFactory(DatapathController.class),
                        DatapathController.Name());

        flowControllerActor =
                startActor(getGuiceAwareFactory(FlowController.class),
                        FlowController.Name());

        simulationControllerActor =
                startActor(getGuiceAwareFactory(SimulationController.class),
                        SimulationController.Name());

        routingManagerActor =
                startActor(getGuiceAwareFactory(RoutingManagerActor.class),
                        RoutingManagerActor.Name());

        if (config.getMidolmanEnableMonitoring()) {
            monitoringActor = startActor(getGuiceAwareFactory(MonitoringActor.class),
                    MonitoringActor.Name());
        }

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
            stopActor(simulationControllerActor);
            stopActor(routingManagerActor);

            if (config.getMidolmanEnableMonitoring()) {
                stopActor(monitoringActor);
            }

            log.debug("Stopping the actor system");
            actorSystem.shutdown();
            notifyStopped();
        } catch (Exception e) {
            log.error("Exception", e);
            notifyFailed(e);
        }
    }

    public Props getGuiceAwareFactory(Class<? extends Actor> actorClass) {
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

    private ActorRef startTopActor(Props actorProps, String actorName) {
        ActorRef actorRef = null;

        try {
            log.debug("Starting actor {}", actorName);
            actorRef = actorSystem.actorOf(actorProps, actorName);

            log.debug("Started at {}", actorRef);
        } catch (Exception e) {
            log.error("Failed {}", e);
        }

        return actorRef;
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

    protected ActorRef makeActorRef(Props actorProps, String actorName)
            throws Exception {
        Timeout tout = new Timeout(Duration.parse("3 seconds"));
        Future<Object> actorFuture = ask(
                supervisorActor, new StartChild(actorProps, actorName), tout);
        return (ActorRef) Await.result(actorFuture, tout.duration());
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
