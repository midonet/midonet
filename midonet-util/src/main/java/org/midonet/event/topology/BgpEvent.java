/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.event.topology;

import org.midonet.event.topology.AbstractVirtualTopologyEvent;

import java.util.UUID;

public class BgpEvent extends AbstractVirtualTopologyEvent {

    private static final String eventKey = "org.midonet.event.topology.Bgp";

    public BgpEvent() {
        super(eventKey);
    }

    @Override
    public void update(UUID id, Object data) {
        throw new UnsupportedOperationException();
    }

    /**
     * Overloaded delete method to take data in the 2nd arg
     *
     * @param id
     * @param data
     */
    public void delete(UUID id, Object data) {
        handleEvent("DELETE", id, data);
    }

    public void routeCreate(UUID id, Object data) {
        handleEvent("ROUTE_CREATE", id, data);
    }

    public void routeDelete(UUID id, Object data) {
        handleEvent("ROUTE_DELETE", id, data);
    }
}
