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
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.midonet.odp.OpenVSwitch;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.FlowMatches;
import org.midonet.odp.flows.*;
import org.midonet.packets.*;

public class WildcardMatch implements Cloneable {

    private EnumSet<Field> usedFields = EnumSet.noneOf(Field.class);
    private EnumSet<Field> seenFields = EnumSet.noneOf(Field.class);

    public enum Field {
        InputPortNumber,
        TunnelKey,
        EthSrc,
        EthDst,
        EtherType,
        NetworkSrc,
        NetworkDst,
        NetworkProto,
        NetworkTTL,
        NetworkTOS,
        FragmentType,
        SrcPort,
        DstPort,
        // MM-custom fields below this point
        IcmpId,
        IcmpData,
        VlanId
    }

    public static final Field[] IcmpFields = { Field.IcmpData, Field.IcmpId };

    private short getLayer(Field f) {
        switch(f) {
            case EthSrc:
            case EthDst:
            case EtherType:
            case VlanId:
                return 2;
            case NetworkDst:
            case NetworkProto:
            case NetworkSrc:
            case NetworkTOS:
            case NetworkTTL:
            case FragmentType:
                return 3;
            case DstPort:
            case SrcPort:
            case IcmpData:
            case IcmpId:
                return 4;
            case InputPortNumber:
            case TunnelKey:
            default:
                return 1;
        }
    }

    /**
     * WARNING: this is giving you a reference to the private Set itself. Be
     * aware that the Match is free to wipe the contents of the set off at
     * any point (e.g.: when a match is returned to a pool and its contents
     * cleared), so you should consider making a copy if you need to keep the
     * set around (e.g.: if it's used as a key to the WildcardMatch table
     * like in FlowController). This is unless you are holding a reference to
     * the enclosing ManagedWildcardFlow for as long as you're going to keep
     * the Set in use. (Even so, you should probably make a copy since the
     * match modify it).
     *
     * The reason for not returning a defensive copy is mainly efficiency, we
     * prefer not to incurr in the copy overhead always, and rather rely on
     * clients doing the copy if it's really needed.
     *
     * @return the set of Fields that have been set in this instance.
     */
    @Nonnull
    public Set<Field> getUsedFields() {
        return usedFields;
    }

    /**
     * WARNING: the same restrictions of getUsedFields() apply here too.
     * @return the set of Fields that have been read from this instance.
     */
    @Nonnull
    public Set<Field> getSeenFields() {
        return seenFields;
    }

    private short inputPortNumber = 0;
    private long tunnelKey = 0L;
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

    private boolean trackSeenFields = true;

    /**
     * Log the fact that <pre>field</pre> has been seen in this match. Will
     * NOT log it if <pre>doNotTrackSeenFields</pre> has last been called.
     */
    private void fieldSeen(Field field) {
        if (this.trackSeenFields) {
            this.seenFields.add(field);
        }
    }

    /**
     * Track seen fields from now on.
     */
    public void doTrackSeenFields() {
        this.trackSeenFields = true;
    }

    /**
     * Do not track seen fields from now on.
     */
    public void doNotTrackSeenFields() {
        this.trackSeenFields = false;
    }

    /**
     * Reports the highest layer seen among the fields accessed on this match.
     * E.g.: if only ethSrc was seen, it'll return 2, if also
     * srcPort was seen, it'll return 4.
     *
     * @return
     */
    public short highestLayerSeen() {
        short layer = 0;
        short fLayer;
        for (Field f : this.seenFields) {
            fLayer = getLayer(f);
            layer = (fLayer > layer) ? fLayer : layer;
        }
        return layer;
    }

    public boolean userspaceFieldsSeen() {
        for (Field f : IcmpFields) {
            if (seenFields.contains(f))
                return true;
        }
        return false;
    }

    public void propagateUserspaceFieldsOf(WildcardMatch that) {
        for (Field f : IcmpFields) {
            if (that.seenFields.contains(f))
                this.seenFields.add(f);
        }
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
        usedFields.clear();
        usedFields.addAll(that.usedFields);
        trackSeenFields = that.trackSeenFields;
        seenFields.clear();
        seenFields.addAll(that.seenFields);
    }

    /**
     * Clears the contents of the fields. It will also empty the collection of
     * used fields.
     */
    public void clear() {
        this.usedFields.clear();
        this.icmpData = null;
        this.networkSrc = null;
        this.networkDst = null;
        this.ethSrc = null;
        this.ethDst = null;
        this.vlanIds = null;
        this.trackSeenFields = true;
        this.seenFields.clear();
    }

    @Nonnull
    public WildcardMatch setInputPortNumber(short inputPortNumber) {
        usedFields.add(Field.InputPortNumber);
        this.inputPortNumber = inputPortNumber;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetInputPortNumber() {
        usedFields.remove(Field.InputPortNumber);
        this.inputPortNumber = 0;
        return this;
    }

    @Nullable
    public Short getInputPortNumber() {
        return usedFields.contains(Field.InputPortNumber) ? inputPortNumber : null;
    }

    @Nonnull
    public WildcardMatch setTunnelKey(long tunnelKey) {
        this.tunnelKey = tunnelKey;
        usedFields.add(Field.TunnelKey);
        return this;
    }

    @Nonnull
    public WildcardMatch unsetTunnelKey() {
        usedFields.remove(Field.TunnelKey);
        this.tunnelKey = 0L;
        return this;
    }

    public long getTunnelKey() {
        return tunnelKey;
    }

    public boolean isFromTunnel() {
        return usedFields.contains(Field.TunnelKey);
    }

    @Nonnull
    public WildcardMatch setEthSrc(@Nonnull String addr) {
        return setEthSrc(MAC.fromString(addr));
    }

    @Nonnull
    public WildcardMatch setEthSrc(@Nonnull MAC addr) {
        usedFields.add(Field.EthSrc);
        this.ethSrc = addr;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetEthSrc() {
        usedFields.remove(Field.EthSrc);
        this.ethSrc = null;
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
        usedFields.add(Field.EthDst);
        this.ethDst = addr;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetEthDest() {
        usedFields.remove(Field.EthDst);
        this.ethDst = null;
        return this;
    }

    @Nullable
    public MAC getEthDst() {
        fieldSeen(Field.EthDst);
        return ethDst;
    }

    @Nonnull
    public WildcardMatch setEtherType(short etherType) {
        usedFields.add(Field.EtherType);
        this.etherType = etherType;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetEtherType() {
        usedFields.remove(Field.EtherType);
        this.etherType = 0;
        return this;
    }

    @Nullable
    public Short getEtherType() {
        fieldSeen(Field.EtherType);
        return usedFields.contains(Field.EtherType) ? etherType : null;
    }

    @Nonnull
    public WildcardMatch setNetworkSrc(@Nonnull IPAddr addr) {
        usedFields.add(Field.NetworkSrc);
        this.networkSrc = addr;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetNetworkSrc() {
        usedFields.remove(Field.NetworkSrc);
        this.networkSrc = null;
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
        usedFields.add(Field.NetworkDst);
        this.networkDst = addr;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetNetworkDst() {
        usedFields.remove(Field.NetworkDst);
        this.networkDst = null;
        return this;
    }

    @Nullable
    public IPAddr getNetworkDstIP() {
        fieldSeen(Field.NetworkDst);
        return networkDst;
    }

    @Nonnull
    public WildcardMatch setNetworkProto(byte networkProto) {
        usedFields.add(Field.NetworkProto);
        this.networkProto = networkProto;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetNetworkProto() {
        usedFields.remove(Field.NetworkProto);
        this.networkProto = 0;
        return this;
    }

    @Nullable
    public Byte getNetworkProto() {
        fieldSeen(Field.NetworkProto);
        return usedFields.contains(Field.NetworkProto) ? networkProto : null;
    }

    @Nullable
    public Byte getNetworkTOS() {
        fieldSeen(Field.NetworkTOS);
        return usedFields.contains(Field.NetworkTOS) ? networkTOS : null;
    }

    @Nonnull
    public WildcardMatch setNetworkTOS(byte tos) {
        usedFields.add(Field.NetworkTOS);
        this.networkTOS = tos;
        return this;
    }

    @Nonnull
    public WildcardMatch setNetworkTTL(byte networkTTL) {
        usedFields.add(Field.NetworkTTL);
        this.networkTTL = networkTTL;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetNetworkTTL() {
        usedFields.remove(Field.NetworkTTL);
        this.networkTTL = 0;
        return this;
    }

    @Nullable
    public Byte getNetworkTTL() {
        fieldSeen(Field.NetworkTTL);
        return usedFields.contains(Field.NetworkTTL) ? networkTTL : null;
    }

    @Nonnull
    public WildcardMatch setIpFragmentType(IPFragmentType fragmentType) {
        usedFields.add(Field.FragmentType);
        this.ipFragmentType = fragmentType;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetIpFragmentType() {
        usedFields.remove(Field.FragmentType);
        this.ipFragmentType = null;
        return this;
    }

    @Nullable
    public IPFragmentType getIpFragmentType() {
        fieldSeen(Field.FragmentType);
        return ipFragmentType;
    }

    public boolean isFragmented() {
        IPFragmentType type = getIpFragmentType();
        return type == IPFragmentType.First || type == IPFragmentType.Later;
    }

    @Nonnull
    public WildcardMatch setSrcPort(int srcPort) {
        TCP.ensurePortInRange(srcPort);
        usedFields.add(Field.SrcPort);
        this.srcPort = srcPort;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetSrcPort() {
        usedFields.remove(Field.SrcPort);
        this.srcPort = 0;
        return this;
    }

    @Nullable
    public Integer getSrcPort() {
        fieldSeen(Field.SrcPort);
        return usedFields.contains(Field.SrcPort) ? srcPort : null;
    }

    @Nonnull
    public WildcardMatch setDstPort(int dstPort) {
        TCP.ensurePortInRange(dstPort);
        usedFields.add(Field.DstPort);
        this.dstPort = dstPort;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetDstPort() {
        usedFields.remove(Field.DstPort);
        this.dstPort = 0;
        return this;
    }

    @Nullable
    public Integer getDstPort() {
        fieldSeen(Field.DstPort);
        return usedFields.contains(Field.DstPort) ?
                dstPort : null;
    }

    public WildcardMatch setIcmpIdentifier(Short identifier) {
        usedFields.add(Field.IcmpId);
        this.icmpId = identifier;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetIcmpIdentifier() {
        usedFields.remove(Field.IcmpId);
        this.icmpId = 0;
        return this;
    }

    @Nullable
    public Short getIcmpIdentifier() {
        fieldSeen(Field.IcmpId);
        return usedFields.contains(Field.IcmpId) ? icmpId : null;
    }

    @Nullable
    public byte[] getIcmpData() {
        fieldSeen(Field.IcmpData);
        return (icmpData == null) ? null
                                  : Arrays.copyOf(icmpData, icmpData.length);
    }

    @Nonnull
    public WildcardMatch setIcmpData(byte[] icmpData) {
        usedFields.add(Field.IcmpData);
        if (icmpData != null)
            this.icmpData = Arrays.copyOf(icmpData, icmpData.length);
        return this;
    }

    @Nonnull
    public WildcardMatch unsetIcmpData() {
        usedFields.remove(Field.IcmpData);
        this.icmpData = null;
        return this;
    }
    @Nonnull
    public WildcardMatch unsetVlanIds() {
        usedFields.remove(Field.VlanId);
        this.vlanIds.clear();
        return this;
    }
    @Nonnull
    public WildcardMatch addVlanId(short vlanId) {
        if (!usedFields.contains(Field.VlanId)) {
            usedFields.add(Field.VlanId);
        }
        this.vlanIds.add(vlanId);
        return this;
    }

    @Nonnull
    public WildcardMatch addVlanIds(List<Short> vlanIds) {
        if (!usedFields.contains(Field.VlanId)) {
            usedFields.add(Field.VlanId);
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
            usedFields.remove(Field.VlanId);
        return this;
    }

    @Nullable
    public List<Short> getVlanIds() {
        fieldSeen(Field.VlanId);
        return (usedFields.contains(Field.VlanId)) ? vlanIds
                                                   : new ArrayList<Short>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof WildcardMatch)) return false;

        WildcardMatch that = (WildcardMatch) o;

        for (Field field : getUsedFields()) {
            switch (field) {
                case EtherType:
                    if (!isEqual(field, that, etherType, that.etherType))
                        return false;
                    break;

                case FragmentType:
                    if (!isEqual(field, that, ipFragmentType, that.ipFragmentType))
                        return false;
                    break;

                case EthDst:
                    if (!isEqual(field, that, ethDst, that.ethDst))
                        return false;
                    break;

                case EthSrc:
                    if (!isEqual(field, that, ethSrc, that.ethSrc))
                        return false;
                    break;

                case DstPort:
                    if (!isEqual(field, that, dstPort, that.dstPort))
                        return false;
                    break;

                case SrcPort:
                    if (!isEqual(field, that, srcPort, that.srcPort))
                        return false;
                    break;

                case InputPortNumber:
                    if (!isEqual(field, that, inputPortNumber, that.inputPortNumber))
                        return false;
                    break;

                case NetworkDst:
                    if (!isEqual(field, that, networkDst, that.networkDst))
                        return false;
                    break;

                case NetworkSrc:
                    if (!isEqual(field, that, networkSrc, that.networkSrc))
                        return false;
                    break;

                case NetworkProto:
                    if (!isEqual(field, that, networkProto, that.networkProto))
                        return false;
                    break;

                case NetworkTTL:
                    if (!isEqual(field, that, networkTTL, that.networkTTL))
                        return false;
                    break;

                case TunnelKey:
                    if (!isEqual(field, that, tunnelKey, that.tunnelKey))
                        return false;
                    break;

                case IcmpId:
                    if (!isEqual(field, that, icmpId, that.icmpId))
                        return false;
                    break;

                case IcmpData:
                    if (!isEqual(field, that, Objects.hashCode(this.icmpData),
                                              Objects.hashCode(that.icmpData)))
                        return false;
                    break;

                case VlanId:
                    if (!isEqual(field, that, vlanIds, that.vlanIds))
                        return false;
                    break;

            }
        }

        return true;
    }

    private <T> boolean isEqual(Field field, WildcardMatch that, T a, T b) {
        if (usedFields.contains(field)) {
            if (that.usedFields.contains(field))
                return a != null ? a.equals(b) : b == null;
            else
                return false;
        } else {
            return !that.usedFields.contains(field);
        }
    }

    @Override
    public int hashCode() {
        int result = getUsedFields().hashCode();
        for (Field field : getUsedFields()) {
            switch (field) {
                case EtherType:
                    result = 31 * result + etherType;
                    break;
                case FragmentType:
                    result = 31 * result + ipFragmentType.hashCode();
                    break;
                case EthDst:
                    result = 31 * result + ethDst.hashCode();
                    break;
                case EthSrc:
                    result = 31 * result + ethSrc.hashCode();
                    break;
                case DstPort:
                    result = 31 * result + dstPort;
                    break;
                case SrcPort:
                    result = 31 * result + srcPort;
                    break;
                case InputPortNumber:
                    result = 31 * result + inputPortNumber;
                    break;
                case NetworkDst:
                    result = 31 * result + networkDst.hashCode();
                    break;
                case NetworkSrc:
                    result = 31 * result + networkSrc.hashCode();
                    break;
                case NetworkProto:
                    result = 31 * result + networkProto;
                    break;
                case NetworkTTL:
                    result = 31 * result + networkTTL;
                    break;
                case TunnelKey:
                    result = 31 * result + (int)(tunnelKey ^ tunnelKey >>> 32);
                    break;
                case IcmpId:
                    result = 31 * result + icmpId;
                    break;
                case IcmpData:
                    result = 31 * result + Arrays.hashCode(icmpData);
                    break;
                case VlanId:
                    result = 31 * result + vlanIds.hashCode();
                    break;
            }
        }

        return result;
    }

    private String getNetworkProtocolAsString() {
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

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder("WildcardMatch[");
        Set<Field> usedFields = getUsedFields();
        for (Field f: usedFields) {
            switch (f) {
                case EtherType:
                    str.append("ethertype=");
                    str.append(etherType);
                    break;

                case FragmentType:
                    str.append("ipfrag=");
                    str.append(ipFragmentType.toString());
                    break;

                case EthDst:
                    str.append("eth_dst=");
                    str.append(ethDst.toString());
                    break;

                case EthSrc:
                    str.append("eth_src=");
                    str.append(ethSrc.toString());
                    break;

                case DstPort:
                    str.append("transport_dst=");
                    str.append(dstPort);
                    break;

                case SrcPort:
                    str.append("transport_src=");
                    str.append(srcPort);
                    break;

                case InputPortNumber:
                    str.append("inport=");
                    str.append(inputPortNumber);
                    break;

                case NetworkDst:
                    str.append("nw_dst=");
                    str.append(networkDst.toString());
                    break;

                case NetworkSrc:
                    str.append("nw_src=");
                    str.append(networkSrc.toString());
                    break;

                case NetworkProto:
                    str.append("nw_proto=");
                    str.append(getNetworkProtocolAsString());
                    break;

                case NetworkTTL:
                    str.append("nw_ttl=");
                    str.append(networkTTL);
                    break;

                case TunnelKey:
                    str.append("tunnel_id=");
                    str.append(tunnelKey);
                    break;

                case IcmpId:
                    str.append("icmp_id=");
                    str.append(icmpId);
                    break;

                case IcmpData:
                    str.append("icmp_data=");
                    str.append(Arrays.toString(icmpData));
                    break;

                case VlanId:
                    str.append("vlan=");
                    str.append(vlanIds);
                    break;
            }
            str.append(", ");
        }

        // Trim trailing ", "
        if (!usedFields.isEmpty())
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
        // XXX TODO(pino): validate implementation of clone !
        WildcardMatch newClone = new WildcardMatch();

        newClone.usedFields.addAll(usedFields);
        for (Field field : Field.values()) {
            if (usedFields.contains(field)) {
                switch (field) {
                    case EtherType:
                        newClone.etherType = etherType;
                        break;

                    case FragmentType:
                        newClone.ipFragmentType = ipFragmentType;
                        break;

                    case EthDst:
                        newClone.ethDst = ethDst;
                        break;

                    case EthSrc:
                        newClone.ethSrc = ethSrc;
                        break;

                    case DstPort:
                        newClone.dstPort = dstPort;
                        break;

                    case SrcPort:
                        newClone.srcPort = srcPort;
                        break;

                    case InputPortNumber:
                        newClone.inputPortNumber = inputPortNumber;
                        break;

                    case NetworkDst:
                        newClone.networkDst = networkDst;
                        break;

                    case NetworkSrc:
                        newClone.networkSrc = networkSrc;
                        break;

                    case NetworkProto:
                        newClone.networkProto = networkProto;
                        break;

                    case NetworkTOS:
                        newClone.networkTOS = networkTOS;
                        break;

                    case NetworkTTL:
                        newClone.networkTTL = networkTTL;
                        break;

                    case TunnelKey:
                        newClone.tunnelKey = tunnelKey;
                        break;

                    case IcmpId:
                        newClone.icmpId = icmpId;
                        break;

                    case IcmpData:
                        newClone.icmpData =
                            Arrays.copyOf(icmpData, icmpData.length);
                        break;

                    case VlanId:
                        newClone.vlanIds.addAll(vlanIds);
                        break;

                    default:
                        throw new RuntimeException("Cannot clone a " +
                            "WildcardMatch with an unrecognized field: " +
                            field);
                }
            }
        }

        return newClone;
    }

    @Nullable
    public ProjectedWildcardMatch project(Set<WildcardMatch.Field> fields) {
        if (!getUsedFields().containsAll(fields))
            return null;

        return new ProjectedWildcardMatch(fields, this);
    }

    public static WildcardMatch fromFlowMatch(FlowMatch match) {
        return fromFlowKeys(match.getKeys());
    }

    public static WildcardMatch fromFlowKeys(List<FlowKey> keys) {
        WildcardMatch wildcardMatch = new WildcardMatch();
        wildcardMatch.processMatchKeys(keys);

        if(wildcardMatch.getEtherType() == null){
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
                    setInputPortNumber((short) inPort.getInPort());
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
                    setTunnelKey(as(flowKey, FlowKeyTunnel.class).getTunnelID());
                    break;
            }
        }
    }

    private static <Key extends FlowKey> Key as(FlowKey flowKey,
                                                Class<Key> type) {
        return type.cast(flowKey);
    }
}
