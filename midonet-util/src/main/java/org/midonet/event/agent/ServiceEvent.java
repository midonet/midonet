/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.event.agent;

import org.midonet.event.AbstractEvent;

public class ServiceEvent extends AbstractEvent {

    private static final String eventKey = "org.midonet.event.agent.Service";

    public ServiceEvent() {
        super(eventKey);
    }

    public void start() {
        handleEvent("START");
    }

    public void exit() {
        handleEvent("EXIT");
    }
}
