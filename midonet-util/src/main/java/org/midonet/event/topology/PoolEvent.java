/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.event.topology;

import org.midonet.event.topology.AbstractVirtualTopologyEvent;

/**
 * A class for handle events for topology changes in Pools
 */
public class PoolEvent extends AbstractVirtualTopologyEvent {

    private static final String eventKey = "org.midonet.event.topology.Pool";

    public PoolEvent() {
        super(eventKey);
    }
}
