/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.family;

import org.midonet.netlink.NetlinkRequestContext;

public abstract class OvsBaseContext implements NetlinkRequestContext {
    final short commandFamily;
    final byte command;
    public OvsBaseContext(short familyId, byte command) {
        this.commandFamily = familyId;
        this.command = command;
    }
    public short commandFamily() { return commandFamily; }
    public byte command() { return command; }
    abstract public byte version();
}
