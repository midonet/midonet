/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.sdn.dp.ports;

import javax.annotation.Nonnull;

import com.midokura.netlink.NetlinkMessage;
import com.midokura.sdn.dp.Port;

/**
 * Description of a "patch" tunnel datapath port.
 */
public class PatchTunnelPort extends Port<PatchTunnelPortOptions, PatchTunnelPort> {

    public PatchTunnelPort(@Nonnull String name) {
        super(name, Type.Patch);
    }

    @Override
    public PatchTunnelPortOptions newOptions() {
        return new PatchTunnelPortOptions();
    }

    @Override
    protected PatchTunnelPort self() {
        return this;
    }

    static class Attr<T> extends NetlinkMessage.AttrKey<T> {

        public static final Attr<String> OVS_PATCH_ATTR_PEER = attr(1);

        public Attr(int id) {
            super(id);
        }

        static <T> Attr<T> attr(int id) {
            return new Attr<T>(id);
        }
    }
}
