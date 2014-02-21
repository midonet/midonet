/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.event.agent;

import org.midonet.event.AbstractEvent;

public class InterfaceEvent extends AbstractEvent {

    private static final String eventKey = "org.midonet.event.agent.Interface";

    public InterfaceEvent() {
        super(eventKey);
    }

    public void detect(String itf) {
        handleEvent("DETECT", itf);
    }

    public void update(String itf) {
        handleEvent("UPDATE", itf);
    }

    public void delete(String name) {
        handleEvent("DELETE", name);
    }
}
