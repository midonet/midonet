/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.family;

import org.midonet.odp.OpenVSwitch;

/**
 * Abstraction for the NETLINK OvsPacket family of commands and attributes.
 */
public class PacketFamily {

    public final short familyId;

    public final OvsBaseContext contextMiss;
    public final OvsBaseContext contextExec;
    public final OvsBaseContext contextAction;

    public PacketFamily(short familyId) {
        this.familyId = familyId;
        contextMiss = new PacketContext(familyId, OpenVSwitch.Packet.Cmd.Miss);
        contextExec = new PacketContext(familyId, OpenVSwitch.Packet.Cmd.Exec);
        contextAction = new PacketContext(familyId, OpenVSwitch.Packet.Cmd.Action);
    }

    private static class PacketContext extends OvsBaseContext {
        public PacketContext(short familyId, byte command) {
            super(familyId, command);
        }
        @Override
        public byte version() { return OpenVSwitch.Packet.version; }
    }
}
