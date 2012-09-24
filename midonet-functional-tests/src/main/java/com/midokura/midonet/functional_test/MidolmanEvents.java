/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.util.UUID;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MidolmanEvents {
    private final static Logger log =
        LoggerFactory.getLogger(MidolmanEvents.class);
    static ActorSystem system = null;
    static ActorRef observer = null;
    static {
        system = ActorSystem.create("MidolmanObserver",
            ConfigFactory.load().getConfig("observemidolman"));
    }

    public static void startObserver() {
        if (null == observer) {
            observer = system.actorOf(
                new Props(MidolmanObserver.class), "midolmanObserver");
            ActorRef midolman = system.actorFor(
                "akka://MidolmanActors@127.0.0.1:2552/user/remoteServer");
            midolman.tell("LocalPortsStart", observer);
            log.info("Started Midolman observer: {}", observer);
        }
    }

    public interface EventCallback {
        void portStatus(UUID portID, boolean up);
    }

    public static void setObserverCallback(EventCallback cb) {
        observer.tell(cb);
    }
}
