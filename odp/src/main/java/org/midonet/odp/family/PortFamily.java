/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp.family;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.DpPort;
import org.midonet.odp.ports.VxLanTunnelPortOptions;
import org.midonet.odp.OpenVSwitch;

/**
 * Abstraction for the NETLINK OvsVPort family of commands and attributes.
 */
public class PortFamily {

    public static final byte VERSION = OpenVSwitch.Port.version;
    public static final String NAME = OpenVSwitch.Port.Family;
    public static final String MC_GROUP = OpenVSwitch.Port.MCGroup;

    public static final int FALLBACK_MC_GROUP = OpenVSwitch.Port.fallbackMCGroup;

    public interface Attr {

        /* u32 port number within datapath */
        NetlinkMessage.AttrKey<Integer> PORT_NO =
            NetlinkMessage.AttrKey.attr(OpenVSwitch.Port.Attr.PortNo);

        /* u32 OVS_VPORT_TYPE_* constant. */
        NetlinkMessage.AttrKey<Integer> PORT_TYPE =
            NetlinkMessage.AttrKey.attr(OpenVSwitch.Port.Attr.Type);

        /* string name, up to IFNAMSIZ bytes long */
        NetlinkMessage.AttrKey<String> NAME =
            NetlinkMessage.AttrKey.attr(OpenVSwitch.Port.Attr.Name);

        /* nested attributes, varies by vport type */
        NetlinkMessage.AttrKey<VxLanTunnelPortOptions> VXLANOPTIONS =
            NetlinkMessage.AttrKey.attrNested(OpenVSwitch.Port.Attr.Options);

        /* u32 Netlink PID to receive upcalls */
        NetlinkMessage.AttrKey<Integer> UPCALL_PID =
            NetlinkMessage.AttrKey.attr(OpenVSwitch.Port.Attr.UpcallPID);

        /* struct ovs_vport_stats */
        NetlinkMessage.AttrKey<DpPort.Stats> STATS =
            NetlinkMessage.AttrKey.attr(OpenVSwitch.Port.Attr.Stats);

    }

    public final OvsBaseContext contextNew;
    public final OvsBaseContext contextDel;
    public final OvsBaseContext contextGet;
    public final OvsBaseContext contextSet;

    public PortFamily(int familyId) {
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
