/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.event.topology;

import org.midonet.event.topology.AbstractVirtualTopologyEvent;

import java.util.UUID;

public class PortEvent extends AbstractVirtualTopologyEvent {

    public static final String eventKey = "org.midonet.event.topology.Port";

    public PortEvent() {
        super(eventKey);
    }

    public void link(UUID id, Object data) {
        handleEvent("LINK", id, data);
    }

    public void unlink(UUID id, Object data) {
        handleEvent("UNLINK", id, data);
    }

    public void bind(UUID id, Object data) {
        handleEvent("BIND", id, data);
    }

    public void unbind(UUID id) {
        handleEvent("UNBIND", id);
    }
}
