/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.family;

import org.midonet.odp.OpenVSwitch;

/**
 * Abstraction for the NETLINK OvsVPort family of commands and attributes.
 */
public class PortFamily {

    public final OvsBaseContext contextNew;
    public final OvsBaseContext contextDel;
    public final OvsBaseContext contextGet;
    public final OvsBaseContext contextSet;

    public PortFamily(short familyId) {
        contextNew = new PortContext(familyId, OpenVSwitch.Port.Cmd.New);
        contextDel = new PortContext(familyId, OpenVSwitch.Port.Cmd.Del);
        contextGet = new PortContext(familyId, OpenVSwitch.Port.Cmd.Get);
        contextSet = new PortContext(familyId, OpenVSwitch.Port.Cmd.Set);
    }

    private static class PortContext extends OvsBaseContext {
        public PortContext(short familyId, byte command) {
            super(familyId, command);
        }
        @Override
        public byte version() { return OpenVSwitch.Port.version; }
    }
}
