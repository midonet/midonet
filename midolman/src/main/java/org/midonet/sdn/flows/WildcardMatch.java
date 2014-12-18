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

package org.midonet.sdn.flows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.primitives.Longs;

import org.midonet.odp.OpenVSwitch;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.FlowMatches;
import org.midonet.odp.flows.*;
import org.midonet.packets.*;

public class WildcardMatch implements Cloneable {

    protected long usedFields = 0;
    protected long seenFields = 0;

    public enum Field {
        InputPortNumber {
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.inputPortNumber;
            }
            public int hashCode(WildcardMatch wcmatch) {
                return wcmatch.inputPortNumber;
            }
            public boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2) {
                return wcmatch1.inputPortNumber == wcmatch2.inputPortNumber;
            }
        },
        TunnelKey {
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.tunnelKey;
            }
            public int hashCode(WildcardMatch wcmatch) {
                return Longs.hashCode(wcmatch.tunnelKey);
            }
            public boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2) {
                return wcmatch1.tunnelKey == wcmatch2.tunnelKey;
            }
        },
        TunnelSrc {
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.tunnelSrc;
            }
            public int hashCode(WildcardMatch wcmatch) {
                return wcmatch.tunnelSrc;
            }
            public boolean equals(WildcardMatch wcmatch1,
                                  WildcardMatch wcmatch2) {
                return wcmatch1.tunnelSrc == wcmatch2.tunnelSrc;
            }
        },
        TunnelDst {
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.tunnelDst;
            }
            public int hashCode(WildcardMatch wcmatch) {
                return wcmatch.tunnelDst;
            }
            public boolean equals(WildcardMatch wcmatch1,
                                  WildcardMatch wcmatch2) {
                return wcmatch1.tunnelDst == wcmatch2.tunnelDst;
            }
        },
        EthSrc {
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.ethSrc;
            }
            public int hashCode(WildcardMatch wcmatch) {
                return Objects.hash(wcmatch.ethSrc);
            }
            public boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2) {
                return Objects.equals(wcmatch1.ethSrc, wcmatch2.ethSrc);
            }
        },
        EthDst {
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.ethDst;
            }
            public int hashCode(WildcardMatch wcmatch) {
                return Objects.hashCode(wcmatch.ethDst);
            }
            public boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2) {
                return Objects.equals(wcmatch1.ethDst, wcmatch2.ethDst);
            }
        },
        EtherType {
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.etherType;
            }
            public int hashCode(WildcardMatch wcmatch) {
                return wcmatch.etherType;
            }
            public boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2) {
                return wcmatch1.etherType == wcmatch2.etherType;
            }
        },
        VlanId { // MM-custom field
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.vlanIds;
            }
            public int hashCode(WildcardMatch wcmatch) {
                return Objects.hashCode(wcmatch.vlanIds);
            }
            public boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2) {
                return Objects.equals(wcmatch1.vlanIds, wcmatch2.vlanIds);
            }
        },
        NetworkSrc {
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.networkSrc;
            }
            public int hashCode(WildcardMatch wcmatch) {
                return Objects.hashCode(wcmatch.networkSrc);
            }
            public boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2) {
                return Objects.equals(wcmatch1.networkSrc, wcmatch2.networkSrc);
            }
        },
        NetworkDst {
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.networkDst;
            }
            public int hashCode(WildcardMatch wcmatch) {
                return Objects.hashCode(wcmatch.networkDst);
            }
            public boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2) {
                return Objects.equals(wcmatch1.networkDst, wcmatch2.networkDst);
            }
        },
        NetworkProto {
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + getNetworkProtocolAsString(wcmatch.networkProto);
            }
            public int hashCode(WildcardMatch wcmatch) {
                return wcmatch.networkProto;
            }
            public boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2) {
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
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.networkTTL;
            }
            public int hashCode(WildcardMatch wcmatch) {
                return wcmatch.networkTTL;
            }
            public boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2) {
                return wcmatch1.networkTTL == wcmatch2.networkTTL;
            }
        },
        NetworkTOS {
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.networkTOS;
            }
            public int hashCode(WildcardMatch wcmatch) {
                return wcmatch.networkTOS;
            }
            public boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2) {
                return wcmatch1.networkTOS == wcmatch2.networkTOS;
            }
        },
        FragmentType {
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.ipFragmentType;
            }
            public int hashCode(WildcardMatch wcmatch) {
                return wcmatch.ipFragmentType.hashCode();
            }
            public boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2) {
                return wcmatch1.ipFragmentType.equals(wcmatch2.ipFragmentType);
            }
        },
        SrcPort {
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.srcPort;
            }
            public int hashCode(WildcardMatch wcmatch) {
                return wcmatch.srcPort;
            }
            public boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2) {
                return wcmatch1.srcPort == wcmatch2.srcPort;
            }
        },
        DstPort {
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.dstPort;
            }
            public int hashCode(WildcardMatch wcmatch) {
                return wcmatch.dstPort;
            }
            public boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2) {
                return wcmatch1.dstPort == wcmatch2.dstPort;
            }
        },
        IcmpId { // MM-custom field
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.icmpId;
            }
            public int hashCode(WildcardMatch wcmatch) {
                return wcmatch.icmpId;
            }
            public boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2) {
                return wcmatch1.icmpId == wcmatch2.icmpId;
            }
        },
        IcmpData { // MM-custom field
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.getIcmpData();
            }
            public int hashCode(WildcardMatch wcmatch) {
                return Arrays.hashCode(wcmatch.getIcmpData());
            }
            public boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2) {
                return Arrays.equals(wcmatch1.getIcmpData(), wcmatch2.getIcmpData());
            }
        },
        COUNT {
            public String toString(WildcardMatch wcmatch) {
                return toString() + "=" + wcmatch.inputPortNumber;
            }
            public int hashCode(WildcardMatch wcmatch) {
                return 0;
            }
            public boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2) {
                return false;
            }
        };

        public abstract String toString(WildcardMatch wcmatch);
        public abstract int hashCode(WildcardMatch wcmatch);
        public abstract boolean equals(WildcardMatch wcmatch1, WildcardMatch wcmatch2);
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

    public static final long icmpFieldsMask = (1L << Field.IcmpData.ordinal()) |
                                              (1L << Field.IcmpId.ordinal());

    private int inputPortNumber = 0;
    private long tunnelKey = 0L;
    private int tunnelSrc = 0;
    private int tunnelDst = 0;
    private MAC ethSrc;
    private MAC ethDst;
    private short etherType = 0;
    private IPAddr networkSrc;
    private IPAddr networkDst;
    private byte networkProto = 0;
    private byte networkTTL = 0;
    private byte networkTOS = 0;
    private IPFragmentType ipFragmentType = IPFragmentType.None;
    private int srcPort = 0;
    private int dstPort = 0;

    // Extended fields only supported inside MM
    private short icmpId = 0;
    private byte[] icmpData;
    private List<Short> vlanIds = new ArrayList<>();

    private long trackSeenFields = 1L;

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
    private void fieldSeen(Field field) {
        seenFields |= trackSeenFields << field.ordinal();
    }

    public void doTrackSeenFields() {
        trackSeenFields = 1;
    }

    public void doNotTrackSeenFields() {
        trackSeenFields = 0;
    }

     /**
     * Reports the highest layer seen among the fields accessed on this match.
     * E.g.: if only ethSrc was seen, it'll return 2, if also
     * srcPort was seen, it'll return 4. Returns 0 if no field was seen.
     *
     * @return
     */
    public short highestLayerSeen() {
        // Calculate the ordinal of the highest field seen from the expression
        // seenFields |= 1 << {field ordinal}
        int ordinalOfHighest = 63 - Long.numberOfLeadingZeros(seenFields);
        return ordinalOfHighest >= 0 ? fieldLayer[ordinalOfHighest] : 0;
    }

    public boolean userspaceFieldsSeen() {
        return (seenFields & icmpFieldsMask) != 0;
    }

    public void propagateUserspaceFieldsOf(WildcardMatch that) {
        seenFields |= that.seenFields & icmpFieldsMask;
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
    }

   /**
     * Clear a field previously logged as used. Used in testing.
     */
    public void fieldUnused(Field field) {
        usedFields &= ~(1L << field.ordinal());
    }

    public boolean isUsed(Field field) {
        return (usedFields & (1L << field.ordinal())) != 0;
    }

    /**
     * Resets the contents of this WildcardMatch setting them to the values
     * in <pre>that</pre>. The list of used fields will be cleared and all
     * the used fields in <pre>that</pre> copied (but not creating a new
     * collection for the new used fields)
     *
     * @param that
     */
    public void reset(WildcardMatch that) {
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
        ipFragmentType = that.ipFragmentType;
        srcPort = that.srcPort;
        dstPort = that.dstPort;
        icmpId = that.icmpId;
        vlanIds = new ArrayList<>(that.vlanIds);
        if (that.icmpData != null)
            this.setIcmpData(that.icmpData);
        else
            this.icmpData = null;
        usedFields = that.usedFields;
        trackSeenFields = that.trackSeenFields;
        seenFields = that.seenFields;
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
        this.vlanIds = null;
        this.usedFields = 0;
        this.trackSeenFields = 1;
        this.seenFields = 0;
    }

    @Nonnull
    public WildcardMatch setInputPortNumber(int inputPortNumber) {
        fieldUsed(Field.InputPortNumber);
        this.inputPortNumber = inputPortNumber;
        return this;
    }

    public int getInputPortNumber() {
        fieldSeen(Field.InputPortNumber);
        return inputPortNumber;
    }

    @Nonnull
    public WildcardMatch setTunnelKey(long tunnelKey) {
        this.tunnelKey = tunnelKey;
        fieldUsed(Field.TunnelKey);
        return this;
    }

    public long getTunnelKey() {
        fieldSeen(Field.TunnelKey);
        return tunnelKey;
    }

    @Nonnull
    public WildcardMatch setTunnelSrc(int tunnelSrc) {
        fieldUsed(Field.TunnelSrc);
        this.tunnelSrc = tunnelSrc;
        return this;
    }

    public int getTunnelSrc() {
        fieldSeen(Field.TunnelSrc);
        return tunnelSrc;
    }

    @Nonnull
    public WildcardMatch setTunnelDst(int tunnelDst) {
        fieldUsed(Field.TunnelDst);
        this.tunnelDst = tunnelDst;
        return this;
    }

    public int getTunnelDst() {
        fieldSeen(Field.TunnelDst);
        return tunnelDst;
    }

    public boolean isFromTunnel() {
        return isUsed(Field.TunnelKey);
    }

    @Nonnull
    public WildcardMatch setEthSrc(@Nonnull String addr) {
        return setEthSrc(MAC.fromString(addr));
    }

    @Nonnull
    public WildcardMatch setEthSrc(@Nonnull MAC addr) {
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
    public WildcardMatch setEthDst(@Nonnull String addr) {
        return setEthDst(MAC.fromString(addr));
    }

    @Nonnull
    public WildcardMatch setEthDst(@Nonnull MAC addr) {
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
    public WildcardMatch setEtherType(short etherType) {
        fieldUsed(Field.EtherType);
        this.etherType = etherType;
        return this;
    }

    public short getEtherType() {
        fieldSeen(Field.EtherType);
        return etherType;
    }

    @Nonnull
    public WildcardMatch setNetworkSrc(@Nonnull IPAddr addr) {
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
    public WildcardMatch setNetworkDst(@Nonnull IPAddr addr) {
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
    public WildcardMatch setNetworkProto(byte networkProto) {
        fieldUsed(Field.NetworkProto);
        this.networkProto = networkProto;
        return this;
    }

    public byte getNetworkProto() {
        fieldSeen(Field.NetworkProto);
        return networkProto;
    }

    @Nonnull
    public WildcardMatch setNetworkTOS(byte tos) {
        fieldUsed(Field.NetworkTOS);
        this.networkTOS = tos;
        return this;
    }

    public byte getNetworkTOS() {
        fieldSeen(Field.NetworkTOS);
        return networkTOS;
    }

    @Nonnull
    public WildcardMatch setNetworkTTL(byte networkTTL) {
        fieldUsed(Field.NetworkTTL);
        this.networkTTL = networkTTL;
        return this;
    }

    public byte getNetworkTTL() {
        fieldSeen(Field.NetworkTTL);
        return networkTTL;
    }

    @Nonnull
    public WildcardMatch setIpFragmentType(IPFragmentType fragmentType) {
        fieldUsed(Field.FragmentType);
        this.ipFragmentType = fragmentType;
        return this;
    }

    public IPFragmentType getIpFragmentType() {
        fieldSeen(Field.FragmentType);
        return ipFragmentType;
    }

    @Nonnull
    public WildcardMatch setSrcPort(int srcPort) {
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
    public WildcardMatch setDstPort(int dstPort) {
        TCP.ensurePortInRange(dstPort);
        fieldUsed(Field.DstPort);
        this.dstPort = dstPort;
        return this;
    }

    public int getDstPort() {
        fieldSeen(Field.DstPort);
        return dstPort;
    }

    public WildcardMatch setIcmpIdentifier(Short identifier) {
        fieldUsed(Field.IcmpId);
        this.icmpId = identifier;
        return this;
    }

    public short getIcmpIdentifier() {
        fieldSeen(Field.IcmpId);
        return icmpId;
    }

    @Nonnull
    public WildcardMatch setIcmpData(byte[] icmpData) {
        fieldUsed(Field.IcmpData);
        if (icmpData != null)
            this.icmpData = Arrays.copyOf(icmpData, icmpData.length);
        return this;
    }

    @Nullable
    public byte[] getIcmpData() {
        fieldSeen(Field.IcmpData);
        return (icmpData == null) ? null
                                  : Arrays.copyOf(icmpData, icmpData.length);
    }

    @Nonnull
    public WildcardMatch addVlanId(short vlanId) {
        if (!isUsed(Field.VlanId)) {
            fieldUsed(Field.VlanId);
        }
        this.vlanIds.add(vlanId);
        return this;
    }

    @Nonnull
    public WildcardMatch addVlanIds(List<Short> vlanIds) {
        if (!isUsed(Field.VlanId)) {
            fieldUsed(Field.VlanId);
        }
        this.vlanIds.addAll(vlanIds);
        return this;
    }

    @Nonnull
    public WildcardMatch removeVlanId(short vlanId) {
        int index = vlanIds.indexOf(vlanId);
        if (index != -1)
            vlanIds.remove(index);
        if (vlanIds.isEmpty())
            fieldUnused(Field.VlanId);
        return this;
    }

    @Nullable
    public List<Short> getVlanIds() {
        fieldSeen(Field.VlanId);
        return isUsed(Field.VlanId) ? vlanIds : new ArrayList<Short>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof WildcardMatch)) return false;

        WildcardMatch that = (WildcardMatch) o;
        for (Field f : fields) {
            if (isUsed(f) && (!that.isUsed(f) || !f.equals(this, that)))
                return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = Longs.hashCode(usedFields);
        for (Field f : fields) {
            if (isUsed(f))
                result = 31 * result + f.hashCode(this);
        }
        return result;
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder("WildcardMatch[");
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
    public WildcardMatch clone() {
        WildcardMatch newClone = new WildcardMatch();
        newClone.reset(this);
        return newClone;
    }

    @Nullable
    public ProjectedWildcardMatch project(long fields) {
        return (usedFields & fields) == fields
               ? new ProjectedWildcardMatch(fields, this)
               : null;
    }

    public static WildcardMatch fromFlowMatch(FlowMatch match) {
        return fromFlowKeys(match.getKeys());
    }

    public static WildcardMatch fromFlowKeys(List<FlowKey> keys) {
        WildcardMatch wildcardMatch = new WildcardMatch();
        wildcardMatch.processMatchKeys(keys);

        if (!wildcardMatch.isUsed(Field.EtherType)) {
            // Match the empty ether type (802.2)
            wildcardMatch.setEtherType(
                FlowKeys.etherType(FlowKeyEtherType.Type.ETH_P_NONE).getEtherType());
        }
        return wildcardMatch;
    }

    public static WildcardMatch fromEthernetPacket(Ethernet ethPkt) {
        return fromFlowMatch(FlowMatches.fromEthernetPacket(ethPkt));
    }

    private void processMatchKeys(Iterable<FlowKey> flowKeys) {
        for (FlowKey flowKey : flowKeys) {
            switch (flowKey.attrId()) {

                case OpenVSwitch.FlowKey.Attr.Encap:
                    FlowKeyEncap encap = as(flowKey, FlowKeyEncap.class);
                    processMatchKeys(encap.getKeys());
                    break;

                case OpenVSwitch.FlowKey.Attr.Priority:
                    // TODO(pino)
                    break;

                case OpenVSwitch.FlowKey.Attr.InPort:
                    FlowKeyInPort inPort = as(flowKey, FlowKeyInPort.class);
                    setInputPortNumber(inPort.getInPort());
                    break;

                case OpenVSwitch.FlowKey.Attr.Ethernet:
                    FlowKeyEthernet ethernet = as(flowKey,
                                                  FlowKeyEthernet.class);
                    setEthSrc(MAC.fromAddress(ethernet.getSrc()));
                    setEthDst(MAC.fromAddress(ethernet.getDst()));
                    break;

                case OpenVSwitch.FlowKey.Attr.VLan:
                    FlowKeyVLAN vlan = as(flowKey, FlowKeyVLAN.class);
                    addVlanId(vlan.getVLAN());
                    break;

                case OpenVSwitch.FlowKey.Attr.Ethertype:
                    FlowKeyEtherType etherType = as(flowKey,
                                                    FlowKeyEtherType.class);
                    setEtherType(etherType.getEtherType());
                    break;

                case OpenVSwitch.FlowKey.Attr.IPv4:
                    FlowKeyIPv4 ipv4 = as(flowKey, FlowKeyIPv4.class);
                    setNetworkSrc(
                        IPv4Addr.fromInt(ipv4.getSrc()));
                    setNetworkDst(
                        IPv4Addr.fromInt(ipv4.getDst()));
                    setNetworkProto(ipv4.getProto());
                    setIpFragmentType(IPFragmentType.fromByte(ipv4.getFrag()));
                    setNetworkTTL(ipv4.getTtl());
                    break;

                case OpenVSwitch.FlowKey.Attr.IPv6:
                    FlowKeyIPv6 ipv6 = as(flowKey, FlowKeyIPv6.class);
                    int[] intSrc = ipv6.getSrc();
                    int[] intDst = ipv6.getDst();
                    setNetworkSrc(new IPv6Addr(
                        (((long) intSrc[0]) << 32) | (intSrc[1] & 0xFFFFFFFFL),
                        (((long) intSrc[2]) << 32) | (intSrc[3] & 0xFFFFFFFFL)));
                    setNetworkDst(new IPv6Addr(
                        (((long) intDst[0]) << 32) | (intDst[1] & 0xFFFFFFFFL),
                        (((long) intDst[2]) << 32) | (intDst[3] & 0xFFFFFFFFL)));
                    setNetworkProto(ipv6.getProto());
                    setIpFragmentType(ipv6.getFrag());
                    setNetworkTTL(ipv6.getHLimit());
                    break;

                case OpenVSwitch.FlowKey.Attr.TCP:
                    FlowKeyTCP tcp = as(flowKey, FlowKeyTCP.class);
                    setSrcPort(tcp.getSrc());
                    setDstPort(tcp.getDst());
                    setNetworkProto(TCP.PROTOCOL_NUMBER);
                    break;

                case OpenVSwitch.FlowKey.Attr.UDP:
                    FlowKeyUDP udp = as(flowKey, FlowKeyUDP.class);
                    setSrcPort(udp.getUdpSrc());
                    setDstPort(udp.getUdpDst());
                    setNetworkProto(UDP.PROTOCOL_NUMBER);
                    break;

                case OpenVSwitch.FlowKey.Attr.ICMP:
                    FlowKeyICMP icmp = as(flowKey, FlowKeyICMP.class);
                    setSrcPort(icmp.getType());
                    setDstPort(icmp.getCode());
                    if (icmp instanceof FlowKeyICMPEcho) {
                        FlowKeyICMPEcho icmpEcho = ((FlowKeyICMPEcho) icmp);
                        setIcmpIdentifier(icmpEcho.getIdentifier());
                    } else if (icmp instanceof FlowKeyICMPError) {
                        setIcmpData(((FlowKeyICMPError) icmp).getIcmpData());
                    }
                    setNetworkProto(ICMP.PROTOCOL_NUMBER);
                    break;

                case OpenVSwitch.FlowKey.Attr.ICMPv6:
                    // XXX(jlm, s3wong)
                    break;

                case OpenVSwitch.FlowKey.Attr.ARP:
                    FlowKeyARP arp = as(flowKey, FlowKeyARP.class);
                    setNetworkSrc(IPv4Addr.fromInt(arp.getSip()));
                    setNetworkDst(IPv4Addr.fromInt(arp.getTip()));
                    setEtherType(ARP.ETHERTYPE);
                    setNetworkProto((byte) (arp.getOp() & 0xff));
                    break;

                case OpenVSwitch.FlowKey.Attr.ND:
                    // XXX(jlm, s3wong): Neighbor Discovery
                    break;

                case OpenVSwitch.FlowKey.Attr.Tunnel_N:
                    // since ovs 1.9, required for ovs 1.10+
                    // matched in "nested" flagged type id:
                    // FlowKeyAttr<FlowKeyTunnel> tun = attrNest(16); ( neq 16 )
                    FlowKeyTunnel tunnel = as(flowKey, FlowKeyTunnel.class);
                    setTunnelKey(tunnel.getTunnelID());
                    setTunnelSrc(tunnel.getIpv4SrcAddr());
                    setTunnelDst(tunnel.getIpv4DstAddr());
                    break;
            }
        }
    }

    private static <Key extends FlowKey> Key as(FlowKey flowKey,
                                                Class<Key> type) {
        return type.cast(flowKey);
    }
}
