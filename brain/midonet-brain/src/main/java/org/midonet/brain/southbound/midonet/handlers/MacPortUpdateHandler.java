/**
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.midonet.handlers;

import org.midonet.brain.southbound.vtep.events.MacPortUpdate;

/**
 * Defines an interface for a handler for Mido bridges' mac table entry
 * updates.
 */
public interface MacPortUpdateHandler {

    /**
     * Processes a given mac-table entry update.
     * @param update A MAC-port update.
     */
    public void handleMacPortUpdate(MacPortUpdate update);
}