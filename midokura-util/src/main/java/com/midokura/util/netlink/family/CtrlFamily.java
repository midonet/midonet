/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.family;

import com.midokura.util.netlink.Netlink;

/**
 * // TODO: Explain yourself.
 */
public class CtrlFamily extends Netlink.CommandFamily<CtrlFamily.Cmd, CtrlFamily.Attr>{

    public static final int FAMILY_ID = 0x10;
    public static final int VERSION = 1;

    public enum Cmd implements Netlink.ByteConstant {
        UNSPEC(0),
        NEWFAMILY(1), DELFAMILY(2), GETFAMILY(3),
        NEWOPS(4), DELOPS(5), GETOPS(6),
        NEWMCAST_GRP(7), DELMCAST_GRP(8), GETMCAST_GRP(9),
        ;

        byte value;

        Cmd(int value) {
            this.value = (byte)value;
        }

        @Override
        public byte getValue() {
            return value;
        }
    }

    public enum Attr implements Netlink.ShortConstant {

        FAMILY_ID(1), FAMILY_NAME(2), FAMILY_VERSION(3),
        HDRSIZE(4),
        MAXATTR(5),
        OPS(6),
        MCAST_GROUPS(7),

        MCAST_GRP_NAME(1), MCAST_GRP_ID(2);

        short value;

        private Attr(int value) {
            this.value = (short) value;
        }

        @Override
        public short getValue() {
            return value;
        }
    }

    public CtrlFamily() {
        super(FAMILY_ID, VERSION);
    }
}
