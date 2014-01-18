/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.family;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.Datapath;
import org.midonet.odp.OpenVSwitch;

/**
 * Abstraction for the NETLINK OvsDatapath family of commands and attributes.
 */
public class DatapathFamily {

    public static final byte VERSION = OpenVSwitch.Datapath.version;
    public static final String NAME = OpenVSwitch.Datapath.Family;
    public static final String MC_GROUP = OpenVSwitch.Datapath.MCGroup;

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

    public final OvsBaseContext contextNew;
    public final OvsBaseContext contextDel;
    public final OvsBaseContext contextGet;
    public final OvsBaseContext contextSet;

    public DatapathFamily(int familyId) {
        contextNew = new DatapathContext(familyId, OpenVSwitch.Datapath.Cmd.New);
        contextDel = new DatapathContext(familyId, OpenVSwitch.Datapath.Cmd.Del);
        contextGet = new DatapathContext(familyId, OpenVSwitch.Datapath.Cmd.Get);
        contextSet = new DatapathContext(familyId, OpenVSwitch.Datapath.Cmd.Set);
    }

    private static class DatapathContext extends OvsBaseContext {
        public DatapathContext(int familyId, int command) {
            super(familyId, command);
        }
        @Override
        public byte version() { return (byte) OpenVSwitch.Datapath.version; }
    }
}
