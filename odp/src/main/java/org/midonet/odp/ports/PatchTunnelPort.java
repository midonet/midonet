/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.ports;

import javax.annotation.Nonnull;

import org.midonet.netlink.NetlinkMessage;
import org.midonet.odp.Port;

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
