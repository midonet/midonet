/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.ports;

import javax.annotation.Nonnull;

import com.midokura.util.netlink.NetlinkMessage;
import com.midokura.util.netlink.dp.Port;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class PatchTunnelPort extends Port<PatchTunnelPort.Options, PatchTunnelPort> {

    public PatchTunnelPort(@Nonnull String name) {
        super(name, Type.Patch);
    }

    @Override
    protected PatchTunnelPort self() {
        return this;
    }

    public class Options extends AbstractPortOptions {

        String peer;

        public Options setPeer(String peer) {
            this.peer = peer;
            return this;
        }

        @Override
        public void processWithBuilder(NetlinkMessage.Builder builder) {
            if ( peer != null ) {
                builder.addAttr(Attr.OVS_PATCH_ATTR_PEER, peer);
            }
        }
    }

    static class Attr<T> extends NetlinkMessage.Attr<T> {

        public static final Attr<String> OVS_PATCH_ATTR_PEER = attr(1);

        public Attr(int id) {
            super(id);
        }

        static <T> Attr<T> attr(int id) {
            return new Attr<T>(id);
        }
    }
}
