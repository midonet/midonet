/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.family;

import org.midonet.netlink.NetlinkRequestContext;

abstract class OvsBaseContext implements NetlinkRequestContext {
    final short commandFamily;
    final byte command;
    public OvsBaseContext(int familyId, int command) {
        this.commandFamily = (short) familyId;
        this.command = (byte) command;
    }
    public short commandFamily() { return commandFamily; }
    public byte command() { return command; }
    abstract public byte version();
}
