/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.services;

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

import org.midonet.midolman.DatapathController;
import org.midonet.midolman.DeduplicationActor;
import org.midonet.midolman.FlowController;
import org.midonet.midolman.NetlinkCallbackDispatcher;
import org.midonet.midolman.SupervisorActor;
import org.midonet.midolman.SupervisorActor.StartChild;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.monitoring.MonitoringActor;
import org.midonet.midolman.routingprotocols.RoutingManagerActor;
import org.midonet.midolman.topology.VirtualToPhysicalMapper;
import org.midonet.midolman.topology.VirtualTopologyActor;

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
    ActorRef monitoringActor;
    ActorRef routingManagerActor;
    ActorRef deduplicationActor;
    ActorRef netlinkCallbackDispatcher;

    @Override
    protected void doStart() {
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

        routingManagerActor =
                startActor(getGuiceAwareFactory(RoutingManagerActor.class),
                        RoutingManagerActor.Name());

        deduplicationActor =
                startActor(getGuiceAwareFactory(DeduplicationActor.class),
                        DeduplicationActor.Name());

        netlinkCallbackDispatcher =
                startActor(getGuiceAwareFactory(NetlinkCallbackDispatcher.class),
                        NetlinkCallbackDispatcher.Name());

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
            stopActor(routingManagerActor);
            if (config.getMidolmanEnableMonitoring()) {
                stopActor(monitoringActor);
            }
            stopActor(supervisorActor);

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
            Future<Boolean> stopFuture = Patterns.gracefulStop(actorRef,
                Duration.create(100, TimeUnit.MILLISECONDS), actorSystem);
            Await.result(
                stopFuture, Duration.create(150, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            log.warn("Actor {} didn't stop on time. ");
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
        Future<Object> actorFuture = Patterns.ask(
                supervisorActor, new StartChild(actorProps, actorName), tout);
        return (ActorRef) Await.result(actorFuture, tout.duration());
    }

    public void initProcessing() throws Exception {
        log.debug("Sending Initialization message to datapath controller.");
        datapathControllerActor.tell(DatapathController.initializeMsg());
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
