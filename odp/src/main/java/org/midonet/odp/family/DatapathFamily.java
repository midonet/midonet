/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.family;

import org.midonet.netlink.Netlink;
import org.midonet.netlink.NetlinkMessage;
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

    public DatapathFamily(int familyId) {
        super(familyId, VERSION);
    }
}
