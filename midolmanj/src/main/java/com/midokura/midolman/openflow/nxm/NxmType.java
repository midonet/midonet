/*
 * @(#)NxmType.java        1.6 11/12/27
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

public enum NxmType {

    NXM_OF_IN_PORT(0, (byte) 0, (short) 2, false, OfInPortNxmEntry.class),
    NXM_OF_ETH_DST(0, (byte) 1, (short) 6, true, OfEthDstNxmEntry.class),
    NXM_OF_ETH_SRC(0, (byte) 2, (short) 6, false, OfEthSrcNxmEntry.class),
    NXM_OF_ETH_TYPE(0, (byte) 3, (short) 2, false, OfEthTypeNxmEntry.class),
    NXM_OF_VLAN_TCI(0, (byte) 4, (short) 2, true, OfVlanTciNxmEntry.class),
    NXM_OF_IP_TOS(0, (byte) 5, (short) 1, false, OfIpTosNxmEntry.class),
    NXM_OF_IP_PROTO(0, (byte) 6, (short) 1, false, OfIpProtoNxmEntry.class),
    NXM_OF_IP_SRC(0, (byte) 7, (short) 4, true, OfIpSrcNxmEntry.class),
    NXM_OF_IP_DST(0, (byte) 8, (short) 4, true, OfIpDstNxmEntry.class),
    // TCP ports are not maskable until OVS 1.6.
    NXM_OF_TCP_SRC(0, (byte) 9, (short) 2, false, OfTcpSrcNxmEntry.class),
    NXM_OF_TCP_DST(0, (byte) 10, (short) 2, false, OfTcpDstNxmEntry.class),
    // UDP ports are not maskable until OVS 1.6.
    NXM_OF_UDP_SRC(0, (byte) 11, (short) 2, false, OfUdpSrcNxmEntry.class),
    NXM_OF_UDP_DST(0, (byte) 12, (short) 2, false, OfUdpDstNxmEntry.class),
    NXM_OF_ICMP_TYPE(0, (byte) 13, (short) 1, false, OfIcmpTypeNxmEntry.class),
    NXM_OF_ICMP_CODE(0, (byte) 14, (short) 1, false, OfIcmpCodeNxmEntry.class),
    NXM_OF_ARP_OP(0, (byte) 15, (short) 2, false, OfArpOpNxmEntry.class),
    NXM_OF_ARP_SPA(0, (byte) 16, (short) 4, true, OfArpSrcPacketNxmEntry.class),
    NXM_OF_ARP_TPA(0, (byte) 17, (short) 4, true, OfArpTargetPacketNxmEntry.class),
    NXM_NX_TUN_ID(1, (byte) 16, (short) 8, true, OfNxTunIdNxmEntry.class),
    NXM_NX_COOKIE(1, (byte) 30, (short) 8, true, OfNxCookieNxmEntry.class);

    private static final Map<Integer, NxmType> lookup;

    static {
        lookup = new HashMap<Integer, NxmType>();
        for (NxmType v : EnumSet.allOf(NxmType.class)) {
            lookup.put(v.getVal(), v);
        }
    }

    private int val;
    private int vendorId;
    private byte field;
    private Class<? extends NxmEntry> klass;
    private short len;
    private boolean maskable;

    private NxmType(int vendorId, byte field, short len, boolean maskable,
                    Class<? extends NxmEntry> klass) {
        this.vendorId = vendorId;
        this.field = field;
        this.klass = klass;
        this.val = ((vendorId << 7) | field);
        this.len = len;
        this.maskable = maskable;
    }

    public int getVal() {
        return val;
    }

    public short getLen() {
        return len;
    }

    public int getVendorId() {
        return vendorId;
    }

    public byte getField() {
        return field;
    }

    public static NxmType get(int val) {
        return lookup.get(val);
    }

    public int makeHeader(boolean hasMask) {
        int maskVal = (isMaskable() && hasMask) ? 1 : 0;
        return (vendorId << 16) | (field << 9) | (maskVal << 8) | len;
    }

    public boolean isMaskable() {
        return maskable;
    }

    @Override
    public String toString() {
        return "vendor=" + vendorId + ", field=" + field + ", len=" + len;
    }

    public NxmEntry makeNxmEntry(byte[] value, boolean hasMask, byte[] mask) {
        NxmEntry entry;
        try {
            entry = klass.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("NxmEntry needs default constructor " +
                    klass.getCanonicalName(), e);
        }
        return entry.fromRawEntry(new NxmRawEntry(this, value, hasMask, mask));
    }
}
