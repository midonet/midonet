/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.family;

import org.midonet.netlink.Netlink;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.DpPort;
import org.midonet.odp.ports.VxLanTunnelPortOptions;
import org.midonet.odp.OpenVSwitch;

/**
 * Abstraction for the NETLINK OvsVPort family of commands and attributes.
 */
public class PortFamily extends
        Netlink.CommandFamily<PortFamily.Cmd, PortFamily.Attr<PortFamily>>{

    public static final byte VERSION = OpenVSwitch.Port.version;
    public static final String NAME = OpenVSwitch.Port.Family;
    public static final String MC_GROUP = OpenVSwitch.Port.MCGroup;

    public static final int FALLBACK_MC_GROUP = OpenVSwitch.Port.fallbackMCGroup;

    public enum Cmd implements Netlink.ByteConstant {

        NEW(OpenVSwitch.Port.Cmd.New),
        DEL(OpenVSwitch.Port.Cmd.Del),
        GET(OpenVSwitch.Port.Cmd.Get),
        SET(OpenVSwitch.Port.Cmd.Set);

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
        public static final Attr<Integer> PORT_NO =
            attr(OpenVSwitch.Port.Attr.PortNo);

        /* u32 OVS_VPORT_TYPE_* constant. */
        public static final Attr<Integer> PORT_TYPE =
            attr(OpenVSwitch.Port.Attr.Type);

        /* string name, up to IFNAMSIZ bytes long */
        public static final Attr<String> NAME =
            attr(OpenVSwitch.Port.Attr.Name);

        /* nested attributes, varies by vport type */
        public static final Attr<VxLanTunnelPortOptions> VXLANOPTIONS =
            attrNested(OpenVSwitch.Port.Attr.Options);

        /* u32 Netlink PID to receive upcalls */
        public static final Attr<Integer> UPCALL_PID =
            attr(OpenVSwitch.Port.Attr.UpcallPID);

        /* struct ovs_vport_stats */
        public static final Attr<DpPort.Stats> STATS =
            attr(OpenVSwitch.Port.Attr.Stats);


        private Attr(int id, boolean nested) {
            super(id, nested);
        }

        static <T> Attr<T> attr(int id) {
            return new Attr<T>(id, false);
        }

        static <T> Attr<T> attrNested(int id) {
            return new Attr<T>(id, true);
        }
    }

    public final OvsBaseContext contextNew;
    public final OvsBaseContext contextDel;
    public final OvsBaseContext contextGet;
    public final OvsBaseContext contextSet;

    public PortFamily(int familyId) {
        super(familyId, VERSION);
        contextNew = new PortContext(familyId, OpenVSwitch.Port.Cmd.New);
        contextDel = new PortContext(familyId, OpenVSwitch.Port.Cmd.Del);
        contextGet = new PortContext(familyId, OpenVSwitch.Port.Cmd.Get);
        contextSet = new PortContext(familyId, OpenVSwitch.Port.Cmd.Set);
    }

    private static class PortContext extends OvsBaseContext {
        public PortContext(int familyId, int command) {
            super(familyId, command);
        }
        @Override
        public byte version() { return (byte) OpenVSwitch.Port.version; }
    }
}
