/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.services;

import java.util.UUID;
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
import static akka.pattern.Patterns.gracefulStop;

import com.midokura.midolman.DatapathController;
import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.guice.ComponentInjectorHolder;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.vrn.VirtualToPhysicalMapper;
import com.midokura.midolman.vrn.VirtualTopologyActor;
import static com.midokura.midolman.DatapathController.getInitialize;
import static com.midokura.packets.IntIPv4.fromString;

/**
 * Midolman actors coordinator internal service.
 * <p/>
 * It can start the actor system, spawn the initial actors, kill them when it's
 * time to shutdown.
 */
public class MidolmanActorsService extends AbstractService {

    private static final Logger log = LoggerFactory
        .getLogger(MidolmanActorsService.class);

    @Inject
    MidolmanConfig config;

    @Inject
    Directory midonetDirectory;

    @Inject
    Injector injector;

    ActorSystem actorSystem;

    ActorRef virtualTopologyActor;
    ActorRef datapathControllerActor;
    ActorRef virtualToPhysicalActor;

    @Override
    protected void doStart() {
        ComponentInjectorHolder.setInjector(injector);

        log.info("Booting up actors service");

        log.debug("Creating actors system.");
        actorSystem = ActorSystem.create("midolmanActors");

        log.debug("Spawning the VirtualTopologyActor");
        virtualTopologyActor =
            actorSystem.actorOf(getVirtualTopologyActorFactory(),
                                VirtualTopologyActor.Name());
        log.debug("Spawned at {}", virtualTopologyActor);

        log.debug("Spawning the VirtualToPhysicalMapper");
        virtualToPhysicalActor =
            actorSystem.actorOf(getVirtualToPhysicalMapperActorFactory(),
                                VirtualToPhysicalMapper.Name());
        log.debug("Spawned at {}", virtualToPhysicalActor);

        log.debug("Spawning the DatapathController");
        datapathControllerActor =
            actorSystem.actorOf(getDatapathControllerProps(),
                                DatapathController.Name());
        log.debug("Spawned at {}", datapathControllerActor);


        notifyStarted();
        log.info("Actors system started");
    }

    @Override
    protected void doStop() {
        try {
            stopActor(datapathControllerActor);
            stopActor(virtualTopologyActor);
            stopActor(virtualToPhysicalActor);

            log.debug("Stopping the actor system");
            actorSystem.shutdown();
            notifyStopped();
        } catch (Exception e) {
            log.error("Exception", e);
            notifyFailed(e);
        }
    }

    private Props getDatapathControllerProps() {
	return new Props(DatapathController.class);
//                new UntypedActorFactory() {
//                    @Override
//                    public Actor create() {
//                        return new DatapathController();
//                    }
//                }
//            );
    }

    private Props getVirtualTopologyActorFactory() {
        return
            new Props(
                new UntypedActorFactory() {
                    @Override
                    public Actor create() {
                        return new VirtualTopologyActor(
                            midonetDirectory,
                            config.getMidolmanRootKey(),
                            fromString(config.getOpenFlowPublicIpAddress())
                        );
                    }
                });
    }

    private Props getVirtualToPhysicalMapperActorFactory() {
        return
            new Props(
                new UntypedActorFactory() {
                    @Override
                    public Actor create() {
                        return new VirtualToPhysicalMapper();
                    }
                });
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

    public void initProcessing() throws Exception {
        log.debug("Sending Initialization message to datapath controller.");

        Timeout timeout = new Timeout(Duration.parse("1 second"));

        Await.result(
            Patterns.ask(datapathControllerActor, getInitialize(), timeout),
            timeout.duration());
    }

    public ActorSystem system() {
        return actorSystem;
    }
}
