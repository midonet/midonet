/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.event.topology;

import org.midonet.event.topology.AbstractVirtualTopologyEvent;

import java.util.UUID;


public class TunnelZoneEvent extends AbstractVirtualTopologyEvent {

    private static final String eventKey =
            "org.midonet.event.topology.TunnelZone";

    public TunnelZoneEvent() {
        super(eventKey);
    }

    public void memberCreate(UUID id, Object data) {
        handleEvent("MEMBER_CREATE", id, data);
    }

    public void memberDelete(UUID id, Object data) {
        handleEvent("MEMBER_DELETE", id, data);
    }
}
