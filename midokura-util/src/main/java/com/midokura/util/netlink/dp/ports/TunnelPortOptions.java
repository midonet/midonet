/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp.ports;

import com.midokura.util.netlink.NetlinkMessage;

/**
* Abstract class that encapsulates options for a tunnel port.
*/
public abstract class TunnelPortOptions<Options extends TunnelPortOptions<Options>>
    extends AbstractPortOptions
{
    public enum Flag {
        /**
         * Checksum packets.
         */
        TNL_F_CSUM(0x01),

        /**
         * Inherit ToS from inner packet.
         */
        TNL_F_TOS_INHERIT(0x02),

        /**
         * Inherit TTL from inner packet.
         */
        TNL_F_TTL_INHERIT(0x04),

        /**
         * Inherit DF bit from inner packet.
         */
        TNL_F_DF_INHERIT(0x08),

        /**
         * Set DF bit if inherit off or not IP.
         */
        TNL_F_DF_DEFAULT(0x10),

        /**
         * Enable path MTU discovery.
         */
        TNL_F_PMTUD(0x20),

        /**
         * Enable tunnel header caching.
         */
        TNL_F_HDR_CACHE(0x40),

        /**
         * Traffic is IPsec encrypted.
         */
        TNL_F_IPSEC(0x80);

        private int value;

        private Flag(int value) {
            this.value = value;
        }
    }

    int flags;
    byte[] dstIPv4;
    byte[] srcIPv4;
    Long outKey;
    Long inKey;
    private String name;
    private Byte tos;
    private Byte ttl;

    public Options setName(String name) {
        this.name = name;
        return self();
    }

    public Options setDestinationIPv4(byte[] destinationIPv4) {
        this.dstIPv4 = destinationIPv4;
        return self();
    }

    public Options setSourceIPv4(byte[] sourceIPv4) {
        this.srcIPv4 = sourceIPv4;
        return self();
    }

    public Options setFlags(Flag... flags) {
        this.flags = 0;
        for (Flag flag : flags) {
            this.flags |= flag.value;
        }

        return self();
    }

    public Options setTos(byte tos) {
        this.tos = tos;
        return self();
    }

    public Options setTTL(byte ttl) {
        this.ttl = ttl;
        return self();
    }

    public Options setOutKey(long key) {
        this.outKey = key;
        return self();
    }

    public Options setInKey(long key) {
        this.inKey = key;
        return self();
    }

    protected abstract Options self();

    static class Attr<T> extends NetlinkMessage.Attr<T> {

        public static final Attr<Integer> OVS_TUNNEL_ATTR_FLAGS = attr(1);
        public static final Attr<byte[]> OVS_TUNNEL_ATTR_DST_IPV4 = attr(2);
        public static final Attr<byte[]> OVS_TUNNEL_ATTR_SRC_IPV4 = attr(2);
        public static final Attr<Long> OVS_TUNNEL_ATTR_OUT_KEY = attr(3);
        public static final Attr<Long> OVS_TUNNEL_ATTR_IN_KEY = attr(4);
        public static final Attr<Byte> OVS_TUNNEL_ATTR_TOS = attr(5);
        public static final Attr<Byte> OVS_TUNNEL_ATTR_TTL = attr(6);

        public Attr(int id) {
            super(id);
        }

        static <T> Attr<T> attr(int id) {
            return new Attr<T>(id);
        }
    }

    @Override
    public void processWithBuilder(NetlinkMessage.Builder builder) {
        builder.addAttr(Attr.OVS_TUNNEL_ATTR_FLAGS, flags);
        builder.addAttr(Attr.OVS_TUNNEL_ATTR_DST_IPV4, dstIPv4);

        if (this.srcIPv4 != null) {
            builder.addAttr(Attr.OVS_TUNNEL_ATTR_SRC_IPV4, srcIPv4);
        }
        if (this.outKey != null) {
            builder.addAttr(Attr.OVS_TUNNEL_ATTR_OUT_KEY, outKey);
        }
        if (this.inKey != null) {
            builder.addAttr(Attr.OVS_TUNNEL_ATTR_IN_KEY, inKey);
        }
        if (this.tos != null) {
            builder.addAttr(Attr.OVS_TUNNEL_ATTR_TOS, tos);
        }
        if (this.ttl != null) {
            builder.addAttr(Attr.OVS_TUNNEL_ATTR_TTL, ttl);
        }
    }
}


