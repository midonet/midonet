/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.family;

import org.midonet.netlink.Netlink;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.NetlinkRequestContext;
import org.midonet.odp.Datapath;
import org.midonet.odp.OpenVSwitch;

/**
 * Abstraction for the NETLINK OvsDatapath family of commands and attributes.
 */
public class DatapathFamily extends
        Netlink.CommandFamily<DatapathFamily.Cmd,
            DatapathFamily.Attr<DatapathFamily>> {

    public static final byte VERSION = OpenVSwitch.Datapath.version;
    public static final String NAME = OpenVSwitch.Datapath.Family;
    public static final String MC_GROUP = OpenVSwitch.Datapath.MCGroup;

    public enum Cmd implements Netlink.ByteConstant {

        NEW(OpenVSwitch.Datapath.Cmd.New),
        DEL(OpenVSwitch.Datapath.Cmd.Del),
        GET(OpenVSwitch.Datapath.Cmd.Get),
        SET(OpenVSwitch.Datapath.Cmd.Set);

        byte value;

        private Cmd(int value) {
            this.value = (byte)value;
        }

        @Override
        public byte getValue() {
            return value;
        }
    }

    public static class Attr<T> extends NetlinkMessage.AttrKey<T> {

        public static final Attr<String> NAME =
            attr(OpenVSwitch.Datapath.Attr.Name);

        public static final Attr<Integer> UPCALL_PID =
            attr(OpenVSwitch.Datapath.Attr.UpcallPID);

        public static final Attr<Datapath.Stats> STATS =
            attr(OpenVSwitch.Datapath.Attr.Stat);

        public Attr(int id) {
            super(id);
        }

        static <T> Attr<T> attr(int id) {
            return new Attr<T>(id);
        }
    }

    public final NetlinkRequestContext contextNew;
    public final NetlinkRequestContext contextDel;
    public final NetlinkRequestContext contextGet;
    public final NetlinkRequestContext contextSet;

    public DatapathFamily(int familyId) {
        super(familyId, VERSION);
        contextNew = new DatapathContext(familyId, OpenVSwitch.Datapath.Cmd.New);
        contextDel = new DatapathContext(familyId, OpenVSwitch.Datapath.Cmd.Del);
        contextGet = new DatapathContext(familyId, OpenVSwitch.Datapath.Cmd.Get);
        contextSet = new DatapathContext(familyId, OpenVSwitch.Datapath.Cmd.Set);
    }

    private static class DatapathContext implements NetlinkRequestContext {
        final short commandFamily;
        final byte command;
        public DatapathContext(int familyId, int command) {
            this.commandFamily = (short) familyId;
            this.command = (byte) command;
        }
        public short commandFamily() { return commandFamily; }
        public byte version() { return (byte) OpenVSwitch.Datapath.version; }
        public byte command() { return command; }
    }
}
