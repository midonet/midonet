/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.odp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.primitives.Longs;

import org.midonet.odp.flows.*;
import org.midonet.packets.*;

import static org.midonet.util.collection.ArrayListUtil.resetWith;

/**
 * An ovs datapath flow match object. Contains a list of FlowKey instances.
 *
 * @see FlowKey
 * @see org.midonet.odp.flows.FlowKeys
 */
public class FlowMatch {

    public enum Field {
        InputPortNumber {
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" + wcmatch.inputPortNumber;
            }
            public int hashCode(FlowMatch wcmatch) {
                return wcmatch.inputPortNumber;
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return wcmatch1.inputPortNumber == wcmatch2.inputPortNumber;
            }
        },
        TunnelKey {
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" + wcmatch.tunnelKey;
            }
            public int hashCode(FlowMatch wcmatch) {
                return Longs.hashCode(wcmatch.tunnelKey);
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return wcmatch1.tunnelKey == wcmatch2.tunnelKey;
            }
        },
        TunnelSrc {
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" + IPv4Addr.intToString(wcmatch.tunnelSrc);
            }
            public int hashCode(FlowMatch  wcmatch) {
                return wcmatch.tunnelSrc;
            }
            public boolean equals(FlowMatch  wcmatch1,
                                  FlowMatch  wcmatch2) {
                return wcmatch1.tunnelSrc == wcmatch2.tunnelSrc;
            }
        },
        TunnelDst {
            public String toString(FlowMatch  wcmatch) {
                return toString() + "=" + IPv4Addr.intToString(wcmatch.tunnelDst);
            }
            public int hashCode(FlowMatch  wcmatch) {
                return wcmatch.tunnelDst;
            }
            public boolean equals(FlowMatch  wcmatch1,
                                  FlowMatch  wcmatch2) {
                return wcmatch1.tunnelDst == wcmatch2.tunnelDst;
            }
        },
        TunnelTOS {
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" + wcmatch.tunnelTOS;
            }
            public int hashCode(FlowMatch wcmatch) {
                return wcmatch.tunnelTOS;
            }
            public boolean equals(FlowMatch wcmatch1,
                                  FlowMatch wcmatch2) {
                return wcmatch1.tunnelTOS == wcmatch2.tunnelTOS;
            }
        },
        TunnelTTL {
            public String toString(FlowMatch  wcmatch) {
                return toString() + "=" + (wcmatch.tunnelTTL & 0xff);
            }
            public int hashCode(FlowMatch  wcmatch) {
                return wcmatch.tunnelTTL;
            }
            public boolean equals(FlowMatch  wcmatch1,
                                  FlowMatch  wcmatch2) {
                return wcmatch1.tunnelTTL == wcmatch2.tunnelTTL;
            }
        },
        EthSrc {
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" + wcmatch.ethSrc;
            }
            public int hashCode(FlowMatch wcmatch) {
                return Objects.hash(wcmatch.ethSrc);
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return Objects.equals(wcmatch1.ethSrc, wcmatch2.ethSrc);
            }
        },
        EthDst {
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" + wcmatch.ethDst;
            }
            public int hashCode(FlowMatch wcmatch) {
                return Objects.hashCode(wcmatch.ethDst);
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return Objects.equals(wcmatch1.ethDst, wcmatch2.ethDst);
            }
        },
        EtherType {
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" +  String.format("%04X", wcmatch.etherType);
            }
            public int hashCode(FlowMatch wcmatch) {
                return wcmatch.etherType;
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return wcmatch1.etherType == wcmatch2.etherType;
            }
        },
        VlanId { // MM-custom field
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" + wcmatch.vlanIds;
            }
            public int hashCode(FlowMatch wcmatch) {
                return Objects.hashCode(wcmatch.vlanIds);
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return Objects.equals(wcmatch1.vlanIds, wcmatch2.vlanIds);
            }
        },
        NetworkSrc {
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" + wcmatch.networkSrc;
            }
            public int hashCode(FlowMatch wcmatch) {
                return Objects.hashCode(wcmatch.networkSrc);
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return Objects.equals(wcmatch1.networkSrc, wcmatch2.networkSrc);
            }
        },
        NetworkDst {
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" + wcmatch.networkDst;
            }
            public int hashCode(FlowMatch wcmatch) {
                return Objects.hashCode(wcmatch.networkDst);
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return Objects.equals(wcmatch1.networkDst, wcmatch2.networkDst);
            }
        },
        NetworkProto {
            public String toString(FlowMatch wcmatch) {
                if (wcmatch.getEtherType() == ARP.ETHERTYPE)
                    return "ArpOp=" + wcmatch.networkProto;
                else
                    return toString() + "=" + getNetworkProtocolAsString(wcmatch.networkProto);
            }
            public int hashCode(FlowMatch wcmatch) {
                return wcmatch.networkProto;
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return wcmatch1.networkProto == wcmatch2.networkProto;
            }

            private String getNetworkProtocolAsString(byte networkProto) {
                switch (networkProto) {
                    case 1: return "icmp";
                    case 2: return "igmp";
                    case 4: return "ipv4-encap";
                    case 6: return "tcp";
                    case 8: return "egp";
                    case 9: return "igp";
                    case 17: return "udp";
                    case 33: return "dccp";
                    case 41: return "ipv6-encap";
                    case 43: return "ipv6-route";
                    case 44: return "ipv6-frag";
                    case 47: return "gre";
                    case 50: return "esp";
                    case 51: return "ah";
                    case 58: return "ipv6-icmp";
                    case 59: return "ipv6-nonxt";
                    case 60: return "ipv6-opts";
                    case 94: return "ipip";
                    case 97: return "etherip";
                    case 112: return "vrrp";
                    case 115: return "l2tp";
                    case (byte)0x84: return "sctp";
                    default: return Byte.toString(networkProto);
                }
            }
        },
        NetworkTTL {
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" + (wcmatch.networkTTL & 0xff);
            }
            public int hashCode(FlowMatch wcmatch) {
                return wcmatch.networkTTL;
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return wcmatch1.networkTTL == wcmatch2.networkTTL;
            }
        },
        NetworkTOS {
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" + wcmatch.networkTOS;
            }
            public int hashCode(FlowMatch wcmatch) {
                return wcmatch.networkTOS;
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return wcmatch1.networkTOS == wcmatch2.networkTOS;
            }
        },
        FragmentType {
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" + wcmatch.ipFragmentType;
            }
            public int hashCode(FlowMatch wcmatch) {
                return wcmatch.ipFragmentType.hashCode();
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return wcmatch1.ipFragmentType.equals(wcmatch2.ipFragmentType);
            }
        },
        SrcPort {
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" + wcmatch.srcPort;
            }
            public int hashCode(FlowMatch wcmatch) {
                return wcmatch.srcPort;
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return wcmatch1.srcPort == wcmatch2.srcPort;
            }
        },
        DstPort {
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" + wcmatch.dstPort;
            }
            public int hashCode(FlowMatch wcmatch) {
                return wcmatch.dstPort;
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return wcmatch1.dstPort == wcmatch2.dstPort;
            }
        },
        IcmpId { // MM-custom field
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" + wcmatch.icmpId;
            }
            public int hashCode(FlowMatch wcmatch) {
                return wcmatch.icmpId;
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return wcmatch1.icmpId == wcmatch2.icmpId;
            }
        },
        IcmpData { // MM-custom field
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" + Arrays.toString(wcmatch.icmpData);
            }
            public int hashCode(FlowMatch wcmatch) {
                return Arrays.hashCode(wcmatch.icmpData);
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return Arrays.equals(wcmatch1.icmpData, wcmatch2.icmpData);
            }
        },
        UserspaceMark { // MM-custom field
            public String toString(FlowMatch wcmatch) {
                return "";
            }
            public int hashCode(FlowMatch wcmatch) {
                return 0;
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return false;
            }
        },
        COUNT {
            public String toString(FlowMatch wcmatch) {
                return toString() + "=" + wcmatch.inputPortNumber;
            }
            public int hashCode(FlowMatch wcmatch) {
                return 0;
            }
            public boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2) {
                return false;
            }
        };

        public abstract String toString(FlowMatch wmatch);
        public abstract int hashCode(FlowMatch wmatch);
        public abstract boolean equals(FlowMatch wcmatch1, FlowMatch wcmatch2);
    }

    private static final Field[] fields = Field.values();
    private static final short[] fieldLayer = new short[Field.COUNT.ordinal()];
    static {
        Field[] fields = Field.values();
        for (int i = 0; i < fieldLayer.length; ++i) {
            int ordinal = fields[i].ordinal();
            fieldLayer[i] = ordinal >= Field.SrcPort.ordinal() ? 4
                          : ordinal >= Field.NetworkSrc.ordinal() ? 3
                          : ordinal >= Field.EthSrc.ordinal() ? 2
                          : (short) 1;
        }
    }

    private static final long icmpFieldsMask =
        (1L << Field.IcmpData.ordinal()) | (1L << Field.IcmpId.ordinal());
    private static final long userspaceFieldsMask =
        icmpFieldsMask | (1L << Field.UserspaceMark.ordinal());

    private int inputPortNumber = 0;
    private long tunnelKey = 0L;
    private int tunnelSrc = 0;
    private int tunnelDst = 0;
    private byte tunnelTOS = 0;
    private byte tunnelTTL = 0;
    private MAC ethSrc;
    private MAC ethDst;
    private short etherType = (short) FlowKeyEtherType.Type.ETH_P_NONE.value;
    private IPAddr networkSrc;
    private IPAddr networkDst;
    private byte networkProto = 0;
    private byte networkTTL = 0;
    private byte networkTOS = 0;
    private boolean networkTOSKeySet = false;
    private IPFragmentType ipFragmentType = IPFragmentType.None;
    private int srcPort = 0;
    private int dstPort = 0;

    // Extended fields only supported inside MM
    private int icmpId = 0;
    private byte[] icmpData;
    private ArrayList<Short> vlanIds = new ArrayList<>();

    private long trackSeenFields = 1L;

    private long usedFields = 0;
    private long seenFields = 0;

    private final ArrayList<FlowKey> keys = new ArrayList<>();
    private int hashCode = 0;
    private int connectionHash = 0;

    public FlowMatch() { }

    public FlowMatch(@Nonnull ArrayList<FlowKey> keys) {
        this.addKeys(keys);
    }

    public FlowMatch addKey(FlowKey key) {
        keys.add(FlowKeys.intern(key));
        processMatchKey(key);
        invalidateHashCode();
        return this;
    }

    @Nonnull
    public ArrayList<FlowKey> getKeys() {
        return keys;
    }

    public void addKeys(@Nonnull ArrayList<FlowKey> keys) {
        for (int i = 0; i < keys.size(); ++i) {
            addKey(keys.get(i));
        }
    }

    /**
     * @return the set of Fields that have been read from this instance.
     */
    public long getSeenFields() {
        return seenFields;
    }

    /**
     * Log the fact that <pre>field</pre> has been seen in this match. Will
     * NOT log it if <pre>doNotTrackSeenFields</pre> has last been called.
     */
    public void fieldSeen(Field field) {
        seenFields |= trackSeenFields << field.ordinal();
    }

    public final void fieldUnseen(Field field) {
        seenFields &= ~(1L << field.ordinal());
    }

    public final void clearSeenFields() {
        seenFields = 0;
    }

    /**
     * Informs whether the specified field has been seen. A field is seen
     * if trackSeenFields was set to 1 when the respective accessor was called
     * and also if the field is used.
     * NOTE: We apply the usedFields filter here instead of in the fieldsSeen()
     *       method because this one is infrequently called.
     */
    public boolean isSeen(Field field) {
        return (seenFields & usedFields & (1L << field.ordinal())) != 0;
    }

    public void doTrackSeenFields() {
        trackSeenFields = 1;
    }

    public void doNotTrackSeenFields() {
        trackSeenFields = 0;
    }

    private static short highestLayer(long fields) {
        // Calculate the ordinal of the highest field seen from the expression
        // fields |= 1 << {field ordinal}
        int ordinalOfHighest = 63 - Long.numberOfLeadingZeros(fields);
        return ordinalOfHighest >= 0 ? fieldLayer[ordinalOfHighest] : 0;
    }
     /**
     * Reports the highest layer seen among the fields accessed on this match.
     * E.g.: if only ethSrc was seen, it'll return 2, if also
     * srcPort was seen, it'll return 4. Returns 0 if no field was seen.
     */
    public short highestLayerSeen() {
        return highestLayer(seenFields & usedFields);
    }

    public boolean userspaceFieldsSeen() {
        return (seenFields & userspaceFieldsMask) != 0;
    }

    public boolean hasUserspaceOnlyFields() { // Used for testing only.
        return (usedFields & userspaceFieldsMask) != 0;
    }

    public void propagateSeenFieldsFrom(FlowMatch that) {
        seenFields |= that.seenFields;
    }

    public void allFieldsSeen() {
        seenFields = usedFields;
    }

    /**
     * @return the set of Fields that have been set in this instance.
     */
    public long getUsedFields() {
        return usedFields;
    }

    /**
     * Log the fact that a <pre>field</pre> has been set in this match.
     */
    private void fieldUsed(Field field) {
        usedFields |= 1L << field.ordinal();
        invalidateHashCode();
    }

    /**
     * Clear a field previously logged as used. Used in testing.
     */
    public void fieldUnused(Field field) {
        usedFields &= ~(1L << field.ordinal());
        invalidateHashCode();
    }

    public boolean isUsed(Field field) {
        return (usedFields & (1L << field.ordinal())) != 0;
    }

    /**
     * Resets the contents of this FlowMatch setting them to the values
     * in <pre>that</pre>. The list of used fields will be cleared and all
     * the used fields in <pre>that</pre> copied (but not creating a new
     * collection for the new used fields)
     */
    public void reset(FlowMatch that) {
        setIcmpData(that.icmpData);
        resetWithoutIcmpData(that);
    }

    public void resetWithoutIcmpData(FlowMatch that) {
        inputPortNumber = that.inputPortNumber;
        tunnelKey = that.tunnelKey;
        tunnelSrc = that.tunnelSrc;
        tunnelDst = that.tunnelDst;
        ethSrc = that.ethSrc;
        ethDst = that.ethDst;
        etherType = that.etherType;
        networkSrc = that.networkSrc;
        networkDst = that.networkDst;
        networkProto = that.networkProto;
        networkTTL = that.networkTTL;
        networkTOS = that.networkTOS;
        networkTOSKeySet = that.networkTOSKeySet;
        ipFragmentType = that.ipFragmentType;
        srcPort = that.srcPort;
        dstPort = that.dstPort;
        resetWith(that.vlanIds, vlanIds);
        icmpId = that.icmpId;
        usedFields = that.usedFields;
        trackSeenFields = that.trackSeenFields;
        seenFields = that.seenFields;

        resetWith(that.keys, keys);
        invalidateHashCode();
    }

    /**
     * Clears the contents of the fields. It will also empty the collection of
     * used fields.
     */
    public void clear() {
        this.icmpData = null;
        this.networkSrc = null;
        this.networkDst = null;
        this.ethSrc = null;
        this.ethDst = null;
        vlanIds.clear();
        this.etherType = (short) FlowKeyEtherType.Type.ETH_P_NONE.value;
        this.usedFields = 0;
        this.trackSeenFields = 1;
        this.seenFields = 0;
        keys.clear();
        invalidateHashCode();
    }

    @Nonnull
    public FlowMatch setInputPortNumber(int inputPortNumber) {
        fieldUsed(Field.InputPortNumber);
        this.inputPortNumber = inputPortNumber;
        return this;
    }

    public int getInputPortNumber() {
        fieldSeen(Field.InputPortNumber);
        return inputPortNumber;
    }

    @Nonnull
    public FlowMatch setTunnelKey(long tunnelKey) {
        this.tunnelKey = tunnelKey;
        fieldUsed(Field.TunnelKey);
        return this;
    }

    public long getTunnelKey() {
        fieldSeen(Field.TunnelKey);
        return tunnelKey;
    }

    @Nonnull
    public FlowMatch setTunnelSrc(int tunnelSrc) {
        fieldUsed(Field.TunnelSrc);
        this.tunnelSrc = tunnelSrc;
        return this;
    }

    public int getTunnelSrc() {
        fieldSeen(Field.TunnelSrc);
        return tunnelSrc;
    }

    @Nonnull
    public FlowMatch setTunnelDst(int tunnelDst) {
        fieldUsed(Field.TunnelDst);
        this.tunnelDst = tunnelDst;
        return this;
    }

    public int getTunnelDst() {
        fieldSeen(Field.TunnelDst);
        return tunnelDst;
    }

     @Nonnull
    public FlowMatch setTunnelTOS(byte tunnelTOS) {
        fieldUsed(Field.TunnelTOS);
        this.tunnelTOS = tunnelTOS;
        return this;
    }

    public byte getTunnelTOS() {
        fieldSeen(Field.TunnelTOS);
        return tunnelTOS;
    }

    @Nonnull
    public FlowMatch setTunnelTTL(byte tunnelTTL) {
        fieldUsed(Field.TunnelTTL);
        this.tunnelTTL = tunnelTTL;
        return this;
    }

    public byte getTunnelTTL() {
        fieldSeen(Field.TunnelTTL);
        return tunnelTTL;
    }

    public boolean isFromTunnel() {
        return isUsed(Field.TunnelKey);
    }

    @Nonnull
    public FlowMatch setEthSrc(@Nonnull String addr) {
        return setEthSrc(MAC.fromString(addr));
    }

    @Nonnull
    public FlowMatch setEthSrc(@Nonnull MAC addr) {
        fieldUsed(Field.EthSrc);
        this.ethSrc = addr;
        return this;
    }

    @Nullable
    public MAC getEthSrc() {
        fieldSeen(Field.EthSrc);
        return ethSrc;
    }

    @Nonnull
    public FlowMatch setEthDst(@Nonnull String addr) {
        return setEthDst(MAC.fromString(addr));
    }

    @Nonnull
    public FlowMatch setEthDst(@Nonnull MAC addr) {
        fieldUsed(Field.EthDst);
        this.ethDst = addr;
        return this;
    }

    @Nullable
    public MAC getEthDst() {
        fieldSeen(Field.EthDst);
        return ethDst;
    }

    @Nonnull
    public FlowMatch setEtherType(short etherType) {
        fieldUsed(Field.EtherType);
        this.etherType = etherType;
        return this;
    }

    public short getEtherType() {
        fieldSeen(Field.EtherType);
        return etherType;
    }

    @Nonnull
    public FlowMatch setNetworkSrc(@Nonnull IPAddr addr) {
        fieldUsed(Field.NetworkSrc);
        this.networkSrc = addr;
        return this;
    }

    @Nullable
    public IPAddr getNetworkSrcIP() {
        fieldSeen(Field.NetworkSrc);
        return networkSrc;
    }

    /**
     * @param addr doesn't support network range, just host IP
     * @return
     */
    @Nonnull
    public FlowMatch setNetworkDst(@Nonnull IPAddr addr) {
        fieldUsed(Field.NetworkDst);
        this.networkDst = addr;
        return this;
    }

    @Nullable
    public IPAddr getNetworkDstIP() {
        fieldSeen(Field.NetworkDst);
        return networkDst;
    }

    @Nonnull
    public FlowMatch setNetworkProto(byte networkProto) {
        fieldUsed(Field.NetworkProto);
        this.networkProto = networkProto;
        return this;
    }

    public byte getNetworkProto() {
        fieldSeen(Field.NetworkProto);
        return networkProto;
    }

    @Nonnull
    public FlowMatch setNetworkTOS(byte tos) {
        fieldUsed(Field.NetworkTOS);
        this.networkTOS = tos;
        return this;
    }

    public FlowMatch setNetworkTOSKeySet(boolean keySet) {
        this.networkTOSKeySet = keySet;
        return this;
    }

    public byte getNetworkTOS() {
        fieldSeen(Field.NetworkTOS);
        return networkTOS;
    }

    public boolean getNetworkTOSKeySet() {
        return networkTOSKeySet;
    }

    @Nonnull
    public FlowMatch setNetworkTTL(byte networkTTL) {
        fieldUsed(Field.NetworkTTL);
        this.networkTTL = networkTTL;
        return this;
    }

    public byte getNetworkTTL() {
        fieldSeen(Field.NetworkTTL);
        return networkTTL;
    }

    @Nonnull
    public FlowMatch setIpFragmentType(IPFragmentType fragmentType) {
        fieldUsed(Field.FragmentType);
        this.ipFragmentType = fragmentType;
        return this;
    }

    public IPFragmentType getIpFragmentType() {
        fieldSeen(Field.FragmentType);
        return ipFragmentType;
    }

    @Nonnull
    public FlowMatch setSrcPort(int srcPort) {
        TCP.ensurePortInRange(srcPort);
        fieldUsed(Field.SrcPort);
        this.srcPort = srcPort;
        return this;
    }

    public int getSrcPort() {
        fieldSeen(Field.SrcPort);
        return srcPort;
    }

    @Nonnull
    public FlowMatch setDstPort(int dstPort) {
        TCP.ensurePortInRange(dstPort);
        fieldUsed(Field.DstPort);
        this.dstPort = dstPort;
        return this;
    }

    public int getDstPort() {
        fieldSeen(Field.DstPort);
        return dstPort;
    }

    public FlowMatch setIcmpIdentifier(int identifier) {
        fieldUsed(Field.IcmpId);
        this.icmpId = identifier;
        return this;
    }

    public int getIcmpIdentifier() {
        fieldSeen(Field.IcmpId);
        return icmpId;
    }

    @Nonnull
    public FlowMatch setIcmpData(byte[] icmpData) {
        fieldUsed(Field.IcmpData);
        if (icmpData != null)
            this.icmpData = Arrays.copyOf(icmpData, icmpData.length);
        else
            this.icmpData = null;
        return this;
    }

    @Nullable
    public byte[] getIcmpData() {
        if (getNetworkProto() != ICMP.PROTOCOL_NUMBER)
            return null;
        fieldSeen(Field.IcmpData);
        return (icmpData == null) ? null
                                  : Arrays.copyOf(icmpData, icmpData.length);
    }

    @Nonnull
    public FlowMatch addVlanId(short vlanId) {
        fieldUsed(Field.VlanId);
        this.vlanIds.add(vlanId);
        return this;
    }

    @Nonnull
    public FlowMatch addVlanIds(List<Short> vlanIds) {
        fieldUsed(Field.VlanId);
        this.vlanIds.addAll(vlanIds);
        return this;
    }

    @Nonnull
    public FlowMatch removeVlanId(short vlanId) {
        int index = vlanIds.indexOf(vlanId);
        if (index != -1)
            vlanIds.remove(index);
        fieldSeen(Field.VlanId);
        return this;
    }

    public ArrayList<Short> getVlanIds() {
        fieldSeen(Field.VlanId);
        return vlanIds;
    }

    public void markUserspaceOnly() {
        fieldSeen(Field.UserspaceMark);
    }

    public boolean isVlanTagged() {
        for (int i = 0; i < vlanIds.size(); i += 1)
            if (vlanIds.get(i) != 0)
                return true;
        return false;
    }

    public boolean hasEthernetPcp() {
        return vlanIds.size() == 1 && vlanIds.get(0) == 0;
    }

    public boolean stripEthernetPcp() {
        if (hasEthernetPcp()) {
            removeVlanId((short) 0);
            return true;
        } else {
            return false;
        }
    }

    // TODO(duarte): enhance as needed
    public void applyTo(Ethernet eth) {
        eth.setSourceMACAddress(ethSrc);
        eth.setDestinationMACAddress(ethDst);
        if (etherType == IPv4.ETHERTYPE) {
            IPv4 ip = (IPv4)eth.getPayload();
            ip.setSourceAddress((IPv4Addr) networkSrc);
            ip.setDestinationAddress((IPv4Addr) networkDst);
            ip.setTtl(networkTTL);
            byte proto = ip.getProtocol();
            if (proto == TCP.PROTOCOL_NUMBER) {
                TCP tcp = (TCP)ip.getPayload();
                tcp.setSourcePort(srcPort);
                tcp.setDestinationPort(dstPort);
            } else if (proto == UDP.PROTOCOL_NUMBER) {
                UDP udp = (UDP)ip.getPayload();
                udp.setSourcePort(srcPort);
                udp.setDestinationPort(dstPort);
            } else if (proto == ICMP.PROTOCOL_NUMBER &&
                       !ICMP.isError((byte)srcPort)) {
                ICMP icmp = (ICMP)ip.getPayload();
                icmp.setIdentifier(icmpId);
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof FlowMatch)) return false;

        FlowMatch that = (FlowMatch) o;
        for (Field f : fields) {
            if (isUsed(f) && (!that.isUsed(f) || !f.equals(this, that)))
                return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            int result = Longs.hashCode(usedFields);
            for (Field f : fields) {
                if (isUsed(f))
                    result = 31 * result + f.hashCode(this);
            }
            hashCode = result;
        }
        return hashCode;
    }

    /** Returns a hash code which only uses for its calculation fields that are
     *  part of a stateful L4 connection. This allows for  a consistent result
     *  across multiple matches that belong to the same connection.
     */
    public int connectionHash() {
        if (connectionHash == 0) {
            int connHash;
            if (isFromTunnel()) {
                connHash = Field.TunnelKey.hashCode(this);
                connHash = 31 * connHash + Field.TunnelSrc.hashCode(this);
                connHash = 31 * connHash + Field.TunnelDst.hashCode(this);
            } else if (highestLayer(usedFields) >= 4) {
                connHash = Field.NetworkSrc.hashCode(this);
                connHash = 31 * connHash + Field.NetworkDst.hashCode(this);
                connHash = 31 * connHash + Field.NetworkProto.hashCode(this);
                connHash = 31 * connHash + Field.SrcPort.hashCode(this);
                connHash = 31 * connHash + Field.DstPort.hashCode(this);
                connHash = 31 * connHash + Field.IcmpId.hashCode(this);
            } else {
                connHash = hashCode();
            }
            connectionHash = connHash;
        }
        return connectionHash;
    }

    private void invalidateHashCode() {
        hashCode = 0;
        connectionHash = 0;
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder("FlowMatch[");
        for (Field f : fields) {
            if (isUsed(f)) {
                str.append(f.toString(this));
                str.append(", ");
            }
        }
        if (usedFields != 0)
            str.setLength(str.length() - 2);

        str.append("]");
        return str.toString();
    }

    /**
     * Implement cloneable interface
     */
    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public FlowMatch clone() {
        FlowMatch newClone = new FlowMatch();
        newClone.reset(this);
        return newClone;
    }

    private void processMatchKey(FlowKey flowKey) {
        switch (flowKey.attrId()) {

            case OpenVSwitch.FlowKey.Attr.Encap:
                FlowKeyEncap encap = as(flowKey, FlowKeyEncap.class);
                for (FlowKey k : encap.keys)
                    processMatchKey(k);
                break;

            case OpenVSwitch.FlowKey.Attr.Priority:
                // TODO(pino)
                break;

            case OpenVSwitch.FlowKey.Attr.InPort:
                FlowKeyInPort inPort = as(flowKey, FlowKeyInPort.class);
                setInputPortNumber(inPort.portNo);
                break;

            case OpenVSwitch.FlowKey.Attr.Ethernet:
                FlowKeyEthernet ethernet = as(flowKey,
                                              FlowKeyEthernet.class);
                setEthSrc(MAC.fromAddress(ethernet.eth_src));
                setEthDst(MAC.fromAddress(ethernet.eth_dst));
                break;

            case OpenVSwitch.FlowKey.Attr.VLan:
                FlowKeyVLAN vlan = as(flowKey, FlowKeyVLAN.class);
                addVlanId(vlan.vlan);
                break;

            case OpenVSwitch.FlowKey.Attr.Ethertype:
                FlowKeyEtherType etherType = as(flowKey,
                                                FlowKeyEtherType.class);
                setEtherType(etherType.etherType);
                break;

            case OpenVSwitch.FlowKey.Attr.IPv4:
                FlowKeyIPv4 ipv4 = as(flowKey, FlowKeyIPv4.class);
                setNetworkSrc(
                    IPv4Addr.fromInt(ipv4.ipv4_src));
                setNetworkDst(
                    IPv4Addr.fromInt(ipv4.ipv4_dst));
                setNetworkProto(ipv4.ipv4_proto);
                setIpFragmentType(IPFragmentType.fromByte(ipv4.ipv4_frag));
                setNetworkTTL(ipv4.ipv4_ttl);
                setNetworkTOS(ipv4.ipv4_tos);
                break;

            case OpenVSwitch.FlowKey.Attr.IPv6:
                FlowKeyIPv6 ipv6 = as(flowKey, FlowKeyIPv6.class);
                setNetworkSrc(IPv6Addr.fromInts(ipv6.ipv6_src));
                setNetworkDst(IPv6Addr.fromInts(ipv6.ipv6_dst));
                setNetworkProto(ipv6.ipv6_proto);
                setIpFragmentType(IPFragmentType.fromByte(ipv6.ipv6_frag));
                setNetworkTTL(ipv6.ipv6_hlimit);
                setNetworkTOS(ipv6.ipv6_tclass);
                break;

            case OpenVSwitch.FlowKey.Attr.TCP:
                FlowKeyTCP tcp = as(flowKey, FlowKeyTCP.class);
                setSrcPort(tcp.tcp_src);
                setDstPort(tcp.tcp_dst);
                setNetworkProto(TCP.PROTOCOL_NUMBER);
                break;

            case OpenVSwitch.FlowKey.Attr.UDP:
                FlowKeyUDP udp = as(flowKey, FlowKeyUDP.class);
                setSrcPort(udp.udp_src);
                setDstPort(udp.udp_dst);
                setNetworkProto(UDP.PROTOCOL_NUMBER);
                break;

            case OpenVSwitch.FlowKey.Attr.ICMP:
                FlowKeyICMP icmp = as(flowKey, FlowKeyICMP.class);
                setSrcPort(Unsigned.unsign(icmp.icmp_type));
                setDstPort(Unsigned.unsign(icmp.icmp_code));
                if (icmp instanceof FlowKeyICMPEcho) {
                    setIcmpIdentifier(((FlowKeyICMPEcho) icmp).icmp_id);
                } else if (icmp instanceof FlowKeyICMPError) {
                    setIcmpData(((FlowKeyICMPError) icmp).icmp_data);
                }
                setNetworkProto(ICMP.PROTOCOL_NUMBER);
                break;

            case OpenVSwitch.FlowKey.Attr.ICMPv6:
                // TODO
                break;

            case OpenVSwitch.FlowKey.Attr.ARP:
                FlowKeyARP arp = as(flowKey, FlowKeyARP.class);
                setNetworkSrc(IPv4Addr.fromInt(arp.arp_sip));
                setNetworkDst(IPv4Addr.fromInt(arp.arp_tip));
                setEtherType(ARP.ETHERTYPE);
                setNetworkProto((byte) arp.arp_op);
                break;

            case OpenVSwitch.FlowKey.Attr.ND:
                // TODO
                break;

            case OpenVSwitch.FlowKey.Attr.Tunnel_N:
                // since ovs 1.9, required for ovs 1.10+
                // matched in "nested" flagged type id:
                // FlowKeyAttr<FlowKeyTunnel> tun = attrNest(16); ( neq 16 )
                FlowKeyTunnel tunnel = as(flowKey, FlowKeyTunnel.class);
                setTunnelKey(tunnel.tun_id);
                setTunnelSrc(tunnel.ipv4_src);
                setTunnelDst(tunnel.ipv4_dst);
                setTunnelTOS(tunnel.ipv4_tos);
                setTunnelTTL(tunnel.ipv4_ttl);
                break;
        }
    }

    private static <Key extends FlowKey> Key as(FlowKey flowKey,
                                                Class<Key> type) {
        return type.cast(flowKey);
    }
}
