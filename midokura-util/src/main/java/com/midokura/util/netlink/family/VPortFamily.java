/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.family;

import com.midokura.util.netlink.Netlink;

/**
 * // TODO: Explain yourself.
 */
public class VPortFamily
    extends Netlink.CommandFamily<VPortFamily.Cmd, VPortFamily.Attr>{

    public static final byte VERSION = 1;

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

    public enum Attr implements Netlink.ShortConstant {
        /* u32 port number within datapath */
        OVS_VPORT_ATTR_PORT_NO(1),
        /* u32 OVS_VPORT_TYPE_* constant. */
        OVS_VPORT_ATTR_TYPE(2),
        /* string name, up to IFNAMSIZ bytes long */
        OVS_VPORT_ATTR_NAME(3),
        /* nested attributes, varies by vport type */
        OVS_VPORT_ATTR_OPTIONS(4),
        /* u32 Netlink PID to receive upcalls */
        OVS_VPORT_ATTR_UPCALL_PID(5),
        /* struct ovs_vport_stats */
        OVS_VPORT_ATTR_STATS(6),
        /* hardware address */
        OVS_VPORT_ATTR_ADDRESS(100)
        ;

        private Attr(int value) {
            this.value = (short)value;
        }

        short value;

        @Override
        public short getValue() {
            return value;
        }
    }

    public VPortFamily(int familyId) {
        super(familyId, VERSION);
    }
}
