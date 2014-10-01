/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.event.topology;

/**
 * A class for handle events for topology changes in PoolMembers
 */
public class PoolMemberEvent extends AbstractVirtualTopologyEvent {

    private static final String eventKey = "org.midonet.event.topology.PoolMember";

    public PoolMemberEvent() {
        super(eventKey);
    }
}
