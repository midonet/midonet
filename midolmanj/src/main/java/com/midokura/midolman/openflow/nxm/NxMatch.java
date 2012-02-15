/*
 * @(#)NxMatch.java        1.6 12/1/25
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.openflow.nxm;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.TCP;
import com.midokura.midolman.packets.UDP;

public class NxMatch {

    private final Map<NxmType, NxmEntry> entries;

    public NxMatch() {
        this.entries = new HashMap<NxmType, NxmEntry>();
    }

    private void checkOrSetPrerequisite(NxmEntry entry)
            throws NxmPrerequisiteException {
        NxmType type = entry.getNxmType();
        if (entries.containsKey(type)) {
            if (!entries.get(type).equals(entry))
                throw new NxmPrerequisiteException(type);
        }
        else
            entries.put(type, entry);
    }

    private void addEntry(NxmEntry entry)
            throws NxmDuplicateEntryException {
        if(null != entries.put(entry.getNxmType(), entry))
            throw new NxmDuplicateEntryException(entry.getNxmType());
    }

    public void setInPort(short inPort) throws NxmDuplicateEntryException {
        addEntry(new OfInPortNxmEntry(inPort));
    }

    public void setTunnelId(long tunnelId) throws NxmDuplicateEntryException {
        addEntry(new OfNxTunIdNxmEntry(tunnelId));
    }

    public void setCookie(long cookie) throws NxmDuplicateEntryException {
        addEntry(new OfNxCookieNxmEntry(cookie));
    }

    public void setEthDst(byte[] address) throws NxmDuplicateEntryException {
        addEntry(new OfEthDstNxmEntry(address));
    }

    public void setEthSrc(byte[] address) throws NxmDuplicateEntryException {
        addEntry(new OfEthSrcNxmEntry(address));
    }

    public void setEthType(short type) throws NxmDuplicateEntryException {
        addEntry(new OfEthTypeNxmEntry(type));
    }

    public void setIpProto(byte proto) throws NxmDuplicateEntryException,
            NxmPrerequisiteException {
        checkOrSetPrerequisite(new OfEthTypeNxmEntry(IPv4.ETHERTYPE));
        addEntry(new OfIpProtoNxmEntry(proto));
    }

    public void setIpTos(byte tos) throws NxmDuplicateEntryException,
            NxmPrerequisiteException {
        checkOrSetPrerequisite(new OfEthTypeNxmEntry(IPv4.ETHERTYPE));
        addEntry(new OfIpTosNxmEntry(tos));
    }

    public void setIpDst(InetAddress addr, int maskLen)
            throws NxmDuplicateEntryException, NxmPrerequisiteException {
        checkOrSetPrerequisite(new OfEthTypeNxmEntry(IPv4.ETHERTYPE));
        addEntry(new OfIpDstNxmEntry(addr, maskLen));
    }

    public void setIpSrc(InetAddress addr, int maskLen)
            throws NxmDuplicateEntryException, NxmPrerequisiteException {
        checkOrSetPrerequisite(new OfEthTypeNxmEntry(IPv4.ETHERTYPE));
        addEntry(new OfIpSrcNxmEntry(addr, maskLen));
    }

    public void setTcpDst(short port)
            throws NxmDuplicateEntryException, NxmPrerequisiteException {
        checkOrSetPrerequisite(new OfEthTypeNxmEntry(IPv4.ETHERTYPE));
        checkOrSetPrerequisite(new OfIpProtoNxmEntry(TCP.PROTOCOL_NUMBER));
        addEntry(new OfTcpDstNxmEntry(port));
    }

    public void setTcpSrc(short port)
            throws NxmDuplicateEntryException, NxmPrerequisiteException {
        checkOrSetPrerequisite(new OfEthTypeNxmEntry(IPv4.ETHERTYPE));
        checkOrSetPrerequisite(new OfIpProtoNxmEntry(TCP.PROTOCOL_NUMBER));
        addEntry(new OfTcpSrcNxmEntry(port));
    }

    public void setUdpDst(short port)
            throws NxmDuplicateEntryException, NxmPrerequisiteException {
        checkOrSetPrerequisite(new OfEthTypeNxmEntry(IPv4.ETHERTYPE));
        checkOrSetPrerequisite(new OfIpProtoNxmEntry(UDP.PROTOCOL_NUMBER));
        addEntry(new OfUdpDstNxmEntry(port));
    }

    public void setUdpSrc(short port)
            throws NxmDuplicateEntryException, NxmPrerequisiteException {
        checkOrSetPrerequisite(new OfEthTypeNxmEntry(IPv4.ETHERTYPE));
        checkOrSetPrerequisite(new OfIpProtoNxmEntry(UDP.PROTOCOL_NUMBER));
        addEntry(new OfUdpSrcNxmEntry(port));
    }

    public void setIcmpType(byte type)
            throws NxmDuplicateEntryException, NxmPrerequisiteException {
        checkOrSetPrerequisite(new OfEthTypeNxmEntry(IPv4.ETHERTYPE));
        checkOrSetPrerequisite(new OfIpProtoNxmEntry(ICMP.PROTOCOL_NUMBER));
        addEntry(new OfIcmpTypeNxmEntry(type));
    }

    public void setIcmpCode(byte code)
            throws NxmDuplicateEntryException, NxmPrerequisiteException {
        checkOrSetPrerequisite(new OfEthTypeNxmEntry(IPv4.ETHERTYPE));
        checkOrSetPrerequisite(new OfIpProtoNxmEntry(ICMP.PROTOCOL_NUMBER));
        addEntry(new OfIcmpCodeNxmEntry(code));
    }

    public void setArpOp(short op)
            throws NxmDuplicateEntryException, NxmPrerequisiteException {
        checkOrSetPrerequisite(new OfEthTypeNxmEntry(ARP.ETHERTYPE));
        addEntry(new OfArpOpNxmEntry(op));
    }

    public void setArpSPA(InetAddress addr, int maskLen)
            throws NxmDuplicateEntryException, NxmPrerequisiteException {
        checkOrSetPrerequisite(new OfEthTypeNxmEntry(ARP.ETHERTYPE));
        addEntry(new OfArpSrcPacketNxmEntry(addr, maskLen));
    }

    public void setArpTPA(InetAddress addr, int maskLen)
            throws NxmDuplicateEntryException, NxmPrerequisiteException {
        checkOrSetPrerequisite(new OfEthTypeNxmEntry(ARP.ETHERTYPE));
        addEntry(new OfArpTargetPacketNxmEntry(addr, maskLen));
    }

    public void deserialize(ByteBuffer buff) throws NxmIOException {

        while (buff.hasRemaining()) {
            int header = buff.getInt();
            NxmType type = NxmHeaderCodec.getType(header);
            if (type == null) {
                throw new NxmIOException("No type in the header.");
            }

            short len = NxmHeaderCodec.getLength(header);
            if (type.getLen() != len) {
                throw new NxmIOException("Bad len in the header: " + len
                        + " for the type: " + type);
            }

            boolean hasMask = NxmHeaderCodec.hasMask(header);
            if (!type.isMaskable() && hasMask) {
                throw new NxmIOException("HasMask cannot be set for type: "
                        + type);
            }

            byte[] value = new byte[len];
            buff.get(value);

            byte[] mask = null;
            if (hasMask) {
                mask = new byte[len];
                buff.get(mask);
            }

            this.entries.put(type, type.makeNxmEntry(value, hasMask, mask));
        }
    }

    private int totalLen(List<NxmRawEntry> rawEntries) {
        int totalLen = 0;
        for (NxmRawEntry entry : rawEntries) {
            totalLen += 4 + entry.getValue().length;
            if (entry.hasMask()) {
                totalLen += entry.getMask().length;
            }
        }
        return totalLen;
    }

    public ByteBuffer serialize() {
        ArrayList<NxmRawEntry> rawEntries = new ArrayList<NxmRawEntry>();
        if (entries.containsKey(NxmType.NXM_OF_IN_PORT))
            rawEntries.add(
                    entries.get(NxmType.NXM_OF_IN_PORT).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_NX_TUN_ID))
            rawEntries.add(
                    entries.get(NxmType.NXM_NX_TUN_ID).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_NX_COOKIE))
            rawEntries.add(
                    entries.get(NxmType.NXM_NX_COOKIE).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_OF_ETH_DST))
            rawEntries.add(
                    entries.get(NxmType.NXM_OF_ETH_DST).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_OF_ETH_SRC))
            rawEntries.add(
                    entries.get(NxmType.NXM_OF_ETH_SRC).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_OF_ETH_TYPE))
            rawEntries.add(
                    entries.get(NxmType.NXM_OF_ETH_TYPE).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_OF_ARP_OP))
            rawEntries.add(
                    entries.get(NxmType.NXM_OF_ARP_OP).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_OF_ARP_TPA))
            rawEntries.add(
                    entries.get(NxmType.NXM_OF_ARP_TPA).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_OF_ARP_SPA))
            rawEntries.add(
                    entries.get(NxmType.NXM_OF_ARP_SPA).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_OF_IP_TOS))
            rawEntries.add(
                    entries.get(NxmType.NXM_OF_IP_TOS).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_OF_IP_PROTO))
            rawEntries.add(
                    entries.get(NxmType.NXM_OF_IP_PROTO).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_OF_IP_DST))
            rawEntries.add(
                    entries.get(NxmType.NXM_OF_IP_DST).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_OF_IP_SRC))
            rawEntries.add(
                    entries.get(NxmType.NXM_OF_IP_SRC).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_OF_UDP_DST))
            rawEntries.add(
                    entries.get(NxmType.NXM_OF_UDP_DST).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_OF_UDP_SRC))
            rawEntries.add(
                    entries.get(NxmType.NXM_OF_UDP_SRC).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_OF_TCP_DST))
            rawEntries.add(
                    entries.get(NxmType.NXM_OF_TCP_DST).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_OF_TCP_SRC))
            rawEntries.add(
                    entries.get(NxmType.NXM_OF_TCP_SRC).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_OF_ICMP_CODE))
            rawEntries.add(
                    entries.get(NxmType.NXM_OF_ICMP_CODE).createRawNxmEntry());
        if (entries.containsKey(NxmType.NXM_OF_ICMP_TYPE))
            rawEntries.add(
                    entries.get(NxmType.NXM_OF_ICMP_TYPE).createRawNxmEntry());
        int totalLen = totalLen(rawEntries);
        ByteBuffer buff = ByteBuffer.allocate(totalLen);
        for (NxmRawEntry entry : rawEntries) {
            buff.putInt(entry.getHeader());
            buff.put(entry.getValue());
            if (entry.hasMask()) {
                buff.put(entry.getMask());
            }
        }
        return buff;
    }

    public void clear() {
        this.entries.clear();
    }

    public int size() {
        return this.entries.size();
    }

    public OfNxTunIdNxmEntry getTunnelIdEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_NX_TUN_ID);
        return null == entry? null : OfNxTunIdNxmEntry.class.cast(entry);
    }

    public OfNxCookieNxmEntry getCookieEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_NX_COOKIE);
        return null == entry? null : OfNxCookieNxmEntry.class.cast(entry);
    }

    public OfInPortNxmEntry getInPortEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_OF_IN_PORT);
        return null == entry? null : OfInPortNxmEntry.class.cast(entry);
    }

    public OfEthDstNxmEntry getEthDstEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_OF_ETH_DST);
        return null == entry? null : OfEthDstNxmEntry.class.cast(entry);
    }

    public OfEthSrcNxmEntry getEthSrcEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_OF_ETH_SRC);
        return null == entry? null : OfEthSrcNxmEntry.class.cast(entry);
    }

    public OfEthTypeNxmEntry getEthTypeEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_OF_ETH_TYPE);
        return null == entry? null : OfEthTypeNxmEntry.class.cast(entry);
    }

    public OfArpOpNxmEntry getArpOpEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_OF_ARP_OP);
        return null == entry? null : OfArpOpNxmEntry.class.cast(entry);
    }

    public OfArpSrcPacketNxmEntry getArpSPAEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_OF_ARP_SPA);
        return null == entry? null : OfArpSrcPacketNxmEntry.class.cast(entry);
    }

    public OfArpTargetPacketNxmEntry getArpTPAEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_OF_ARP_TPA);
        return null == entry? null : OfArpTargetPacketNxmEntry.class.cast(entry);
    }

    public OfIpTosNxmEntry getIpTosEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_OF_IP_TOS);
        return null == entry? null : OfIpTosNxmEntry.class.cast(entry);
    }

    public OfIpProtoNxmEntry getIpProtoEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_OF_IP_PROTO);
        return null == entry? null : OfIpProtoNxmEntry.class.cast(entry);
    }

    public OfIpSrcNxmEntry getIpSrcEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_OF_IP_SRC);
        return null == entry? null : OfIpSrcNxmEntry.class.cast(entry);
    }

    public OfIpDstNxmEntry getIpDstEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_OF_IP_DST);
        return null == entry? null : OfIpDstNxmEntry.class.cast(entry);
    }

    public OfTcpSrcNxmEntry getTcpSrcEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_OF_TCP_SRC);
        return null == entry? null : OfTcpSrcNxmEntry.class.cast(entry);
    }

    public OfTcpDstNxmEntry getTcpDstEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_OF_TCP_DST);
        return null == entry? null : OfTcpDstNxmEntry.class.cast(entry);
    }

    public OfUdpSrcNxmEntry getUdpSrcEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_OF_UDP_SRC);
        return null == entry? null : OfUdpSrcNxmEntry.class.cast(entry);
    }

    public OfUdpDstNxmEntry getUdpDstEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_OF_UDP_DST);
        return null == entry? null : OfUdpDstNxmEntry.class.cast(entry);
    }

    public OfIcmpCodeNxmEntry getIcmpCodeEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_OF_ICMP_CODE);
        return null == entry? null : OfIcmpCodeNxmEntry.class.cast(entry);
    }

    public OfIcmpTypeNxmEntry getIcmpTypeEntry() {
        NxmEntry entry = entries.get(NxmType.NXM_OF_ICMP_TYPE);
        return null == entry? null : OfIcmpTypeNxmEntry.class.cast(entry);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder("NxMatch{");
        boolean first = true;
        for (NxmEntry entry : entries.values()) {
            if (!first)
                s.append(", ");
            s.append(entry.toString());
            first = false;
        }
        s.append("}");
        return s.toString();
    }
}