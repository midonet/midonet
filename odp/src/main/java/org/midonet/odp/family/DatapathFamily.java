/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.family;

import org.midonet.odp.OpenVSwitch;

/**
 * Abstraction for the NETLINK OvsDatapath family of commands and attributes.
 */
public class DatapathFamily {

    public final OvsBaseContext contextNew;
    public final OvsBaseContext contextDel;
    public final OvsBaseContext contextGet;
    public final OvsBaseContext contextSet;

    public DatapathFamily(short familyId) {
        contextNew = new DatapathContext(familyId, OpenVSwitch.Datapath.Cmd.New);
        contextDel = new DatapathContext(familyId, OpenVSwitch.Datapath.Cmd.Del);
        contextGet = new DatapathContext(familyId, OpenVSwitch.Datapath.Cmd.Get);
        contextSet = new DatapathContext(familyId, OpenVSwitch.Datapath.Cmd.Set);
    }

    private static class DatapathContext extends OvsBaseContext {
        public DatapathContext(short familyId, byte command) {
            super(familyId, command);
        }
        @Override
        public byte version() { return OpenVSwitch.Datapath.version; }
    }
}
