/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.event.topology;

import org.midonet.event.topology.AbstractVirtualTopologyEvent;

import java.util.UUID;

public class RouterEvent extends AbstractVirtualTopologyEvent {

    private static final String eventKey = "org.midonet.event.topology.Router";

    public RouterEvent() {
        super(eventKey);
    }

    public void routeCreate(UUID id, Object data) {
        handleEvent("ROUTE_CREATE", id, data);
    }

    public void routeDelete(UUID id, UUID routeId) {
        handleEvent("ROUTE_DELETE", id, routeId);
    }
}
