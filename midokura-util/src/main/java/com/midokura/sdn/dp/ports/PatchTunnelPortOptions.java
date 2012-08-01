/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.sdn.dp.ports;

import com.midokura.netlink.NetlinkMessage;
import com.midokura.netlink.messages.BaseBuilder;

/**
* // TODO: mtoader ! Please explain yourself.
*/
public class PatchTunnelPortOptions extends AbstractPortOptions {

    String peer;

    public PatchTunnelPortOptions setPeer(String peer) {
        this.peer = peer;
        return this;
    }

    @Override
    public void serialize(BaseBuilder builder) {
        if ( peer != null ) {
            builder.addAttr(PatchTunnelPort.Attr.OVS_PATCH_ATTR_PEER, peer);
        }
    }

    @Override
    public boolean deserialize(NetlinkMessage message) {
        peer = message.getAttrValue(PatchTunnelPort.Attr.OVS_PATCH_ATTR_PEER);
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        PatchTunnelPortOptions options = (PatchTunnelPortOptions) o;

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
