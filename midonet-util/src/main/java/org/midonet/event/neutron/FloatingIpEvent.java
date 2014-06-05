/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.event.neutron;


import org.midonet.event.topology.AbstractVirtualTopologyEvent;

/**
 * A class for handle for Neutron Floating IP changes.
 */
public class FloatingIpEvent extends AbstractVirtualTopologyEvent {
    private static final String eventKey =
            "org.midonet.event.neutron.FloatingIp";

    public FloatingIpEvent() {
        super(eventKey);
    }
}
