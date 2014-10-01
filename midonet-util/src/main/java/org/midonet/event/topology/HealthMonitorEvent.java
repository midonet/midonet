/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.event.topology;

/**
 * A class for handle events for topology changes in HealthMonitors
 */
public class HealthMonitorEvent extends AbstractVirtualTopologyEvent {

    private static final String eventKey = "org.midonet.event.topology.HealthMonitor";

    public HealthMonitorEvent() {
        super(eventKey);
    }
}
