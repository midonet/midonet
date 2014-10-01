/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.event.topology;

import java.util.UUID;


public class RuleEvent extends AbstractVirtualTopologyEvent {
    private static final String eventKey = "org.midonet.event.topology.Rule";

    public RuleEvent() {
        super(eventKey);
    }

    @Override
    public void update(UUID id, Object data) {
        throw new UnsupportedOperationException();
    }
}
