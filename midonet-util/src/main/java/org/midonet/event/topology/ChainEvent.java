/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.event.topology;

import org.midonet.event.topology.AbstractVirtualTopologyEvent;

import java.util.UUID;

public class ChainEvent extends AbstractVirtualTopologyEvent {

    private static final String eventKey = "org.midonet.event.topology.Chain";

    public ChainEvent() {
        super(eventKey);
    }

    @Override
    public void update(UUID id, Object data) {
        throw new UnsupportedOperationException();
    }
}
