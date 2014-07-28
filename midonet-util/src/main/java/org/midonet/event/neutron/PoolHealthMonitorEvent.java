/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.event.neutron;

import org.midonet.event.topology.AbstractVirtualTopologyEvent;

/**
 * A class for handle events for Neutron HealthMonitor changes.
 */
public class PoolHealthMonitorEvent extends AbstractVirtualTopologyEvent {

    private static final String eventKey =
        "org.midonet.event.neutron.PoolHealthMonitor";

    public PoolHealthMonitorEvent() {
        super(eventKey);
    }
}
