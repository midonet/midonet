/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.netlink.dp.ports;

import javax.annotation.Nonnull;

import com.midokura.netlink.NetlinkMessage;
import com.midokura.netlink.dp.Port;
import com.midokura.netlink.messages.BaseBuilder;

/**
 * Description of a "patch" tunnel datapath port.
 */
public class PatchTunnelPort extends Port<PatchTunnelPort.Options, PatchTunnelPort> {

    public PatchTunnelPort(@Nonnull String name) {
        super(name, Type.Patch);
    }

    @Override
    public Options newOptions() {
        return new Options();
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
        public void serialize(BaseBuilder builder) {
            if ( peer != null ) {
                builder.addAttr(Attr.OVS_PATCH_ATTR_PEER, peer);
            }
        }

        @Override
        public boolean deserialize(NetlinkMessage message) {
            peer = message.getAttrValue(Attr.OVS_PATCH_ATTR_PEER);
            return true;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Options options = (Options) o;

            if (peer != null ? !peer.equals(
                options.peer) : options.peer != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            return peer != null ? peer.hashCode() : 0;
        }

        @Override
        public String toString() {
            return "Options{" +
                "peer='" + peer + '\'' +
                '}';
        }
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
