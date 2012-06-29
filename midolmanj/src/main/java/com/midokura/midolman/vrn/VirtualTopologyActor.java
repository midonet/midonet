/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.vrn;

import akka.actor.ActorContext;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class VirtualTopologyActor extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    @Override
    public void onReceive(Object message) {
        return;
    }

    @Override
    public ActorContext context() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }
}
