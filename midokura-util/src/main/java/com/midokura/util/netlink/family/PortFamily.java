/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.family;

import com.midokura.util.netlink.Netlink;
import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.dp.Port;

/**
 * Abstraction for the NETLINK OvsVPort family of commands and attributes.
 */
public class PortFamily
    extends Netlink.CommandFamily<PortFamily.Cmd, PortFamily.Attr>{

    public static final byte VERSION = 1;
    public static final String NAME = "ovs_vport";
    public static final String MC_GROUP = "ovs_vport";

    public static final int FALLBACK_MC_GROUP = 33;

    public enum Cmd implements Netlink.ByteConstant {
        NEW(1), DEL(2), GET(3), SET(4);

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

        /* u32 port number within datapath */
        public static final Attr<Integer> PORT_NO = attr(1);

        /* u32 OVS_VPORT_TYPE_* constant. */
        public static final Attr<Integer> PORT_TYPE = attr(2);

        /* string name, up to IFNAMSIZ bytes long */
        public static final Attr<String> NAME = attr(3);

        /* nested attributes, varies by vport type */
        public static final Attr<Port.Options> OPTIONS = attr(4);

        /* u32 Netlink PID to receive upcalls */
        public static final Attr<Integer> UPCALL_PID = attr(5);

        /* struct ovs_vport_stats */
        public static final Attr<Port.Stats> STATS = attr(6);

        /* hardware address */
        public static final Attr<byte[]> ADDRESS = attr(100);


        public Attr(int id) {
            super(id);
        }

        static <T> Attr<T> attr(int id) {
            return new Attr<T>(id);
        }
    }

    public PortFamily(int familyId) {
        super(familyId, VERSION);
    }
}
