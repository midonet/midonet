/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */

package org.midonet.odp.family;

import org.midonet.odp.OpenVSwitch;

/**
 * Abstraction for the NETLINK OvsFlow family of commands and attributes.
 */
public class FlowFamily {

    public final OvsBaseContext contextNew;
    public final OvsBaseContext contextDel;
    public final OvsBaseContext contextGet;
    public final OvsBaseContext contextSet;

    public FlowFamily(short familyId) {
        contextNew = new FlowContext(familyId, OpenVSwitch.Flow.Cmd.New);
        contextDel = new FlowContext(familyId, OpenVSwitch.Flow.Cmd.Del);
        contextGet = new FlowContext(familyId, OpenVSwitch.Flow.Cmd.Get);
        contextSet = new FlowContext(familyId, OpenVSwitch.Flow.Cmd.Set);
    }

    private static class FlowContext extends OvsBaseContext {
        public FlowContext(short familyId, byte command) {
            super(familyId, command);
        }
        @Override
        public byte version() { return OpenVSwitch.Flow.version; }
    }
}
