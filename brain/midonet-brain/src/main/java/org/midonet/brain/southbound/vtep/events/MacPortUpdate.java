/**
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep.events;

import java.util.UUID;

import org.midonet.packets.MAC;

/**
 * A class representing an update to MAC-port tables.
 */
public class MacPortUpdate {
    /**
     * An ID of the bridge whose MAC table has been updated.
     */
    final public UUID bridgeId;

    /**
     * A MAC address of the port added / deleted / updated.
     */
    final public MAC mac;

    /**
     * An original port ID. Null when a port is newly created.
     */
    final public UUID oldPortId;

    /**
     * A new port ID. Null when a port is deleted.
     */
    final public UUID newPortId;

    /**
     * @param bridgeId An ID of the bridge whose MAC table has been updated.
     * @param mac A MAC address of the port added / deleted / updated.
     * @param oldPortId An original port ID. Null when a port is newly created.
     * @param newPortId A new port ID. Null when a port is deleted.
     */
    public MacPortUpdate(
            UUID bridgeId, MAC mac, UUID oldPortId, UUID newPortId) {
        this.bridgeId = bridgeId;
        this.mac = mac;
        this.oldPortId = oldPortId;
        this.newPortId = newPortId;
    }
}
