/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.ports;

import org.midonet.packets.MAC;

import java.util.UUID;

/**
 * Representation of MAC-port mapping with VLAN ID.
 */
public class VlanMacPort {
    public MAC macAddress;
    public UUID portId;
    public short vlanId;

    public VlanMacPort(MAC macAddress, UUID portId, short vlanId) {
        this.macAddress = macAddress;
        this.portId = portId;
        this.vlanId = vlanId;
    }
}
