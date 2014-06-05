/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.event.neutron;

import org.midonet.event.topology.AbstractVirtualTopologyEvent;

import java.util.UUID;

/**
 * A class for handle for Neutron Router changes.
 */
public class RouterEvent extends AbstractVirtualTopologyEvent {
    private static final String eventKey =
            "org.midonet.event.neutron.NeutronRouter";

    public RouterEvent() {
        super(eventKey);
    }

    public void interfaceUpdate(UUID id, Object data) {
        handleEvent("INTERFACE_UPDATE", id, data);
    }
}
