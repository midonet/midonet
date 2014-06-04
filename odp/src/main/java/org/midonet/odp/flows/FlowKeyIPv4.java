/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer;

import org.midonet.netlink.BytesUtil;
import org.midonet.odp.OpenVSwitch;
import org.midonet.packets.IPv4Addr;

public class FlowKeyIPv4 implements FlowKey {
    /*__be32*/ private int ipv4_src;
    /*__be32*/ private int ipv4_dst;
    /*__u8*/ private byte ipv4_proto;
    /*__u8*/ private byte ipv4_tos;
    /*__u8*/ private byte ipv4_ttl;
    /*__u8*/ private byte ipv4_frag;    /* One of OVS_FRAG_TYPE_*. */

    private int hashCode = 0;

    // This is used for deserialization purposes only.
    FlowKeyIPv4() { }

    FlowKeyIPv4(int src, int dst, byte protocol, byte typeOfService,
                byte ttl, byte fragmentType) {
        this.ipv4_src = src;
        this.ipv4_dst = dst;
        this.ipv4_proto = protocol;
        this.ipv4_tos = typeOfService;
        this.ipv4_ttl = ttl;
        this.ipv4_frag = fragmentType;
    }

    public int serializeInto(ByteBuffer buffer) {
        buffer.putInt(BytesUtil.instance.reverseBE(ipv4_src));
        buffer.putInt(BytesUtil.instance.reverseBE(ipv4_dst));
        buffer.put(ipv4_proto);
        buffer.put(ipv4_tos);
        buffer.put(ipv4_ttl);
        buffer.put(ipv4_frag);
        return 12;
    }

    @Override
    public boolean deserialize(ByteBuffer buf) {
        try {
            ipv4_src = BytesUtil.instance.reverseBE(buf.getInt());
            ipv4_dst = BytesUtil.instance.reverseBE(buf.getInt());
            ipv4_proto = buf.get();
            ipv4_tos = buf.get();
            ipv4_ttl = buf.get();
            ipv4_frag = buf.get();
            hashCode = 0;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public short attrId() {
        return OpenVSwitch.FlowKey.Attr.IPv4;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        @SuppressWarnings("unchecked")
        FlowKeyIPv4 that = (FlowKeyIPv4) o;

        return (ipv4_dst == that.ipv4_dst)
            && (ipv4_frag == that.ipv4_frag)
            && (ipv4_proto == that.ipv4_proto)
            && (ipv4_src == that.ipv4_src)
            && (ipv4_tos == that.ipv4_tos)
            && (ipv4_ttl == that.ipv4_ttl);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            int result = ipv4_src;
            result = 31 * result + ipv4_dst;
            result = 31 * result + (int) ipv4_proto;
            result = 31 * result + (int) ipv4_tos;
            result = 31 * result + (int) ipv4_ttl;
            result = 31 * result + (int) ipv4_frag;
            hashCode = result;
        }
        return hashCode;
    }

    @Override
    public String toString() {
        return "FlowKeyIPv4{" +
            "ipv4_src=" + IPv4Addr.intToString(ipv4_src) +
            ", ipv4_dst=" + IPv4Addr.intToString(ipv4_dst) +
            ", ipv4_proto=" + ipv4_proto +
            ", ipv4_tos=" + ipv4_tos +
            ", ipv4_ttl=" + ipv4_ttl +
            ", ipv4_frag=" + ipv4_frag +
            '}';
    }

    public int getSrc() {
        return ipv4_src;
    }

    public int getDst() {
        return ipv4_dst;
    }

    public byte getProto() {
        return ipv4_proto;
    }

    public byte getTos() {
        return ipv4_tos;
    }

    public byte getTtl() {
        return ipv4_ttl;
    }

    public byte getFrag() {
        return ipv4_frag;
    }
}

