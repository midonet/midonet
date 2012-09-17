/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;

import com.midokura.midolman.topology.VirtualToPhysicalMapper.LocalPortActive;
import com.midokura.midonet.functional_test.MidolmanEvents.EventCallback;

public class MidolmanObserver extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);
    EventCallback cb = null;

    public void onReceive(Object message) {
        if (message instanceof EventCallback) {
            log.info("Received String message: {}", message);
            cb = EventCallback.class.cast(message);
        } else if (message instanceof LocalPortActive) {
            if (null != cb) {
                LocalPortActive msg = LocalPortActive.class.cast(message);
                cb.portStatus(msg.portID(), msg.active());
            }
        }
        else
            log.error("Received unrecognized message: {}", message);
    }
}