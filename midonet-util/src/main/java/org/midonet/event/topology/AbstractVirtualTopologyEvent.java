/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.event.topology;

import org.midonet.event.AbstractEvent;

import java.util.UUID;

/**
 * Abstract event wrapper class for virtual topology related events, which
 * usually have CREATE, UPDATE, DELETE operations.
 */
public class AbstractVirtualTopologyEvent extends AbstractEvent {

    protected AbstractVirtualTopologyEvent(String key) {
        super(key);
    }

    public void create(UUID id, Object data) {
        handleEvent("CREATE", id, data);
    }

    public void update(UUID id, Object data) {
        handleEvent("UPDATE", id, data);
    }

    public void delete(UUID id) {
        handleEvent("DELETE", id);
    }

    public void delete(UUID id1, UUID id2) {
        handleEvent("DELETE", id1, id2);
    }
}

