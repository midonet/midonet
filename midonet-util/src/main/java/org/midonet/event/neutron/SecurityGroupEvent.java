/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.event.neutron;

import org.midonet.event.topology.AbstractVirtualTopologyEvent;

/**
 * A class for handle for Neutron Security Group changes.
 */
public class SecurityGroupEvent extends AbstractVirtualTopologyEvent {
    private static final String eventKey =
            "org.midonet.event.neutron.SecurityGroup";

    public SecurityGroupEvent() {
        super(eventKey);
    }
}
