/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.odp.ports;

import java.nio.ByteOrder;
import java.util.*;

import org.midonet.packets.Net;
import org.midonet.netlink.NetlinkMessage;
import org.midonet.netlink.messages.BaseBuilder;

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

    private static final Map<Flag, String> flagNames;
    static {
        Map<Flag, String> aMap = new HashMap<Flag, String>();
        aMap.put(Flag.TNL_F_CSUM, "Checksum packets");
        aMap.put(Flag.TNL_F_DF_DEFAULT, "Inherit ToS from inner packet");
        aMap.put(Flag.TNL_F_DF_INHERIT, "Inherit DF bit from inner packet");
        aMap.put(Flag.TNL_F_HDR_CACHE, "Enable tunnel header caching");
        aMap.put(Flag.TNL_F_IPSEC, "Traffic is IPsec encrypted");
        aMap.put(Flag.TNL_F_PMTUD, "Enable path MTU discovery");
        aMap.put(Flag.TNL_F_TOS_INHERIT, "Inherit ToS from inner packet.");
        aMap.put(Flag.TNL_F_TTL_INHERIT, "Inherit TTL from inner packet.");
        flagNames = Collections.unmodifiableMap(aMap);
    }

    int flags;
    Integer dstIPv4;
    Integer srcIPv4;
    Long outKey;
    Long inKey;
    private Byte tos;
    private Byte ttl;

    public Options setDestinationIPv4(int destinationIPv4) {
        this.dstIPv4 = destinationIPv4;
        return self();
    }

    public int getDestinationIPv4() {
        return this.dstIPv4;
    }

    public Options setSourceIPv4(int sourceIPv4) {
        this.srcIPv4 = sourceIPv4;
        return self();
    }

    public int getSourceIPv4() {
        return this.srcIPv4;
    }

    public Options setFlags(Flag... flags) {
        this.flags = 0;
        for (Flag flag : flags) {
            this.flags |= flag.value;
        }

        return self();
    }

    public Set<Flag> getFlags() {
        Set<Flag> activeFlags = new HashSet<Flag>();
        EnumSet<Flag> flags = EnumSet.allOf(Flag.class);
        for (Flag flag : flags) {
            if ((this.flags & flag.value) != 0) {
                activeFlags.add(flag);
            }
        }

        return activeFlags;
    }

    public Options setTos(byte tos) {
        this.tos = tos;
        return self();
    }

    public byte getTos() {
        return this.tos;
    }

    public Options setTTL(byte ttl) {
        this.ttl = ttl;
        return self();
    }

    public byte getTTL() {
        return this.ttl;
    }

    public Options setOutKey(long key) {
        this.outKey = key;
        return self();
    }

    public long getOutKey() {
        return this.outKey;
    }

    public Options setInKey(long key) {
        this.inKey = key;
        return self();
    }

    public long getInKey() {
        return this.inKey;
    }

    protected abstract Options self();

    static class Attr<T> extends NetlinkMessage.AttrKey<T> {

        public static final Attr<Integer> OVS_TUNNEL_ATTR_FLAGS = attr(1);
        public static final Attr<Integer> OVS_TUNNEL_ATTR_DST_IPV4 = attr(2);
        public static final Attr<Integer> OVS_TUNNEL_ATTR_SRC_IPV4 = attr(3);
        public static final Attr<Long> OVS_TUNNEL_ATTR_OUT_KEY = attr(4);
        public static final Attr<Long> OVS_TUNNEL_ATTR_IN_KEY = attr(5);
        public static final Attr<Byte> OVS_TUNNEL_ATTR_TOS = attr(6);
        public static final Attr<Byte> OVS_TUNNEL_ATTR_TTL = attr(7);

        public Attr(int id) {
            super(id);
        }

        static <T> Attr<T> attr(int id) {
            return new Attr<T>(id);
        }
    }

    @Override
    public void serialize(BaseBuilder<?,?> builder) {
        builder.addAttr(Attr.OVS_TUNNEL_ATTR_FLAGS, flags);
        if (this.dstIPv4 != null) {
            builder.addAttr(Attr.OVS_TUNNEL_ATTR_DST_IPV4, dstIPv4, ByteOrder.BIG_ENDIAN);
        }
        if (this.srcIPv4 != null) {
            builder.addAttr(Attr.OVS_TUNNEL_ATTR_SRC_IPV4, srcIPv4, ByteOrder.BIG_ENDIAN);
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

    @Override
    public boolean deserialize(NetlinkMessage message) {
        try {
            Integer flags = message.getAttrValueInt(Attr.OVS_TUNNEL_ATTR_FLAGS);

            if (flags != null) {
                this.flags = flags;
            }

            dstIPv4 = message.getAttrValueInt(Attr.OVS_TUNNEL_ATTR_DST_IPV4,
                          ByteOrder.BIG_ENDIAN);
            srcIPv4 = message.getAttrValueInt(Attr.OVS_TUNNEL_ATTR_SRC_IPV4,
                          ByteOrder.BIG_ENDIAN);
            outKey = message.getAttrValueLong(Attr.OVS_TUNNEL_ATTR_OUT_KEY);
            inKey = message.getAttrValueLong(Attr.OVS_TUNNEL_ATTR_IN_KEY);
            tos = message.getAttrValueByte(Attr.OVS_TUNNEL_ATTR_TOS);
            ttl = message.getAttrValueByte(Attr.OVS_TUNNEL_ATTR_TTL);
        } catch (Exception e) {
            return false;
        }

        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        TunnelPortOptions<?> that = (TunnelPortOptions) o;

        if (flags != that.flags) return false;
        if (dstIPv4 != null ? !dstIPv4.equals(
            that.dstIPv4) : that.dstIPv4 != null)
            return false;
        if (inKey != null ? !inKey.equals(that.inKey) : that.inKey != null)
            return false;
        if (outKey != null ? !outKey.equals(that.outKey) : that.outKey != null)
            return false;
        if (srcIPv4 != null ? !srcIPv4.equals(
            that.srcIPv4) : that.srcIPv4 != null)
            return false;
        if (tos != null ? !tos.equals(that.tos) : that.tos != null)
            return false;
        if (ttl != null ? !ttl.equals(that.ttl) : that.ttl != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = flags;
        result = 31 * result + (dstIPv4 != null ? dstIPv4.hashCode() : 0);
        result = 31 * result + (srcIPv4 != null ? srcIPv4.hashCode() : 0);
        result = 31 * result + (outKey != null ? outKey.hashCode() : 0);
        result = 31 * result + (inKey != null ? inKey.hashCode() : 0);
        result = 31 * result + (tos != null ? tos.hashCode() : 0);
        result = 31 * result + (ttl != null ? ttl.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "TunnelPortOptions{" +
            "flags=" + printFlags() +
            ", dstIPv4=" + Net.convertIntAddressToString(dstIPv4 != null ? dstIPv4 : 0) +
            ", srcIPv4=" + Net.convertIntAddressToString(srcIPv4 != null ? srcIPv4 : 0) +
            ", outKey=" + outKey +
            ", inKey=" + inKey +
            ", tos=" + tos +
            ", ttl=" + ttl +
            '}';
    }

    private String printFlags() {
        StringBuilder sb = new StringBuilder();
        sb.append("[ ");
        Set<Flag> activeFlags = getFlags();
        if (activeFlags.isEmpty()) {
           sb.append("No active flags");
        } else {
            for (Flag f : getFlags()) {
                sb.append(flagNames.get(f));
            }
        }
        sb.append(" ]");
        return sb.toString();
    }
}


