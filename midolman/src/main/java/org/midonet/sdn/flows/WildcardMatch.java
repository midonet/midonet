/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
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
        TunnelID,
        EthernetSource,
        EthernetDestination,
        EtherType,
        NetworkSource,
        NetworkDestination,
        NetworkProtocol,
        NetworkTTL,
        NetworkTOS,
        FragmentType,
        TransportSource,
        TransportDestination,
        // MM-custom fields below this point
        IcmpId,
        IcmpData,
        VlanId
    }

    public static final Field[] IcmpFields = { Field.IcmpData, Field.IcmpId };

    private short getLayer(Field f) {
        switch(f) {
            case EthernetSource:
            case EthernetDestination:
            case EtherType:
            case VlanId:
                return 2;
            case NetworkDestination:
            case NetworkProtocol:
            case NetworkSource:
            case NetworkTOS:
            case NetworkTTL:
            case FragmentType:
                return 3;
            case TransportDestination:
            case TransportSource:
            case IcmpData:
            case IcmpId:
                return 4;
            case InputPortNumber:
            case TunnelID:
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
    private long tunnelID = 0L;
    private MAC ethernetSource;
    private MAC ethernetDestination;
    private short etherType = 0;
    private IPAddr networkSource;
    private IPAddr networkDestination;
    private byte networkProtocol = 0;
    private byte networkTTL = 0;
    private byte networkTOS = 0;
    private IPFragmentType ipFragmentType = IPFragmentType.None;
    private int transportSource = 0;
    private int transportDestination = 0;

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
     * E.g.: if only ethernetSource was seen, it'll return 2, if also
     * transportSource was seen, it'll return 4.
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
        tunnelID = that.tunnelID;
        ethernetSource = that.ethernetSource;
        ethernetDestination = that.ethernetDestination;
        etherType = that.etherType;
        networkSource = that.networkSource;
        networkDestination = that.networkDestination;
        networkProtocol = that.networkProtocol;
        networkTTL = that.networkTTL;
        networkTOS = that.networkTOS;
        ipFragmentType = that.ipFragmentType;
        transportSource = that.transportSource;
        transportDestination = that.transportDestination;
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
        this.networkSource = null;
        this.networkDestination = null;
        this.ethernetSource = null;
        this.ethernetDestination = null;
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
    public WildcardMatch setTunnelID(long tunnelID) {
        this.tunnelID = tunnelID;
        usedFields.add(Field.TunnelID);
        return this;
    }

    @Nonnull
    public WildcardMatch unsetTunnelID() {
        usedFields.remove(Field.TunnelID);
        this.tunnelID = 0L;
        return this;
    }

    @Nullable
    public long getTunnelID() {
        return tunnelID;
    }

    public boolean isFromTunnel() {
        return usedFields.contains(Field.TunnelID);
    }

    @Nonnull
    public WildcardMatch setEthernetSource(@Nonnull String addr) {
        return setEthernetSource(MAC.fromString(addr));
    }

    @Nonnull
    public WildcardMatch setEthernetSource(@Nonnull MAC addr) {
        usedFields.add(Field.EthernetSource);
        this.ethernetSource = addr;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetEthernetSource() {
        usedFields.remove(Field.EthernetSource);
        this.ethernetSource = null;
        return this;
    }

    @Nullable
    public MAC getEthernetSource() {
        fieldSeen(Field.EthernetSource);
        return ethernetSource;
    }

    @Nonnull
    public WildcardMatch setEthernetDestination(@Nonnull String addr) {
        return setEthernetDestination(MAC.fromString(addr));
    }

    @Nonnull
    public WildcardMatch setEthernetDestination(@Nonnull MAC addr) {
        usedFields.add(Field.EthernetDestination);
        this.ethernetDestination = addr;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetEthernetDestination() {
        usedFields.remove(Field.EthernetDestination);
        this.ethernetDestination = null;
        return this;
    }

    @Nullable
    public MAC getEthernetDestination() {
        fieldSeen(Field.EthernetDestination);
        return ethernetDestination;
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
    public WildcardMatch setNetworkSource(@Nonnull IPAddr addr) {
        usedFields.add(Field.NetworkSource);
        this.networkSource = addr;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetNetworkSource() {
        usedFields.remove(Field.NetworkSource);
        this.networkSource = null;
        return this;
    }

    @Nullable
    public IPAddr getNetworkSourceIP() {
        fieldSeen(Field.NetworkSource);
        return networkSource;
    }

    /**
     * @param addr doesn't support network range, just host IP
     * @return
     */
    @Nonnull
    public WildcardMatch setNetworkDestination(@Nonnull IPAddr addr) {
        usedFields.add(Field.NetworkDestination);
        this.networkDestination = addr;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetNetworkDestination() {
        usedFields.remove(Field.NetworkDestination);
        this.networkDestination = null;
        return this;
    }

    @Nullable
    public IPAddr getNetworkDestinationIP() {
        fieldSeen(Field.NetworkDestination);
        return networkDestination;
    }

    @Nonnull
    public WildcardMatch setNetworkProtocol(byte networkProtocol) {
        usedFields.add(Field.NetworkProtocol);
        this.networkProtocol = networkProtocol;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetNetworkProtocol() {
        usedFields.remove(Field.NetworkProtocol);
        this.networkProtocol = 0;
        return this;
    }

    @Nullable
    public Byte getNetworkProtocol() {
        fieldSeen(Field.NetworkProtocol);
        return usedFields.contains(Field.NetworkProtocol) ? networkProtocol : null;
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
    public WildcardMatch setTransportSource(int transportSource) {
        TCP.ensurePortInRange(transportSource);
        usedFields.add(Field.TransportSource);
        this.transportSource = transportSource;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetTransportSource() {
        usedFields.remove(Field.TransportSource);
        this.transportSource = 0;
        return this;
    }

    @Nullable
    public Integer getTransportSource() {
        fieldSeen(Field.TransportSource);
        return usedFields.contains(Field.TransportSource) ? transportSource : null;
    }

    @Nonnull
    public WildcardMatch setTransportDestination(int transportDestination) {
        TCP.ensurePortInRange(transportDestination);
        usedFields.add(Field.TransportDestination);
        this.transportDestination = transportDestination;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetTransportDestination() {
        usedFields.remove(Field.TransportDestination);
        this.transportDestination = 0;
        return this;
    }

    @Nullable
    public Integer getTransportDestination() {
        fieldSeen(Field.TransportDestination);
        return usedFields.contains(Field.TransportDestination) ?
            transportDestination : null;
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

                case EthernetDestination:
                    if (!isEqual(field, that, ethernetDestination, that.ethernetDestination))
                        return false;
                    break;

                case EthernetSource:
                    if (!isEqual(field, that, ethernetSource, that.ethernetSource))
                        return false;
                    break;

                case TransportDestination:
                    if (!isEqual(field, that, transportDestination, that.transportDestination))
                        return false;
                    break;

                case TransportSource:
                    if (!isEqual(field, that, transportSource, that.transportSource))
                        return false;
                    break;

                case InputPortNumber:
                    if (!isEqual(field, that, inputPortNumber, that.inputPortNumber))
                        return false;
                    break;

                case NetworkDestination:
                    if (!isEqual(field, that, networkDestination, that.networkDestination))
                        return false;
                    break;

                case NetworkSource:
                    if (!isEqual(field, that, networkSource, that.networkSource))
                        return false;
                    break;

                case NetworkProtocol:
                    if (!isEqual(field, that, networkProtocol, that.networkProtocol))
                        return false;
                    break;

                case NetworkTTL:
                    if (!isEqual(field, that, networkTTL, that.networkTTL))
                        return false;
                    break;

                case TunnelID:
                    if (!isEqual(field, that, tunnelID, that.tunnelID))
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
                case EthernetDestination:
                    result = 31 * result + ethernetDestination.hashCode();
                    break;
                case EthernetSource:
                    result = 31 * result + ethernetSource.hashCode();
                    break;
                case TransportDestination:
                    result = 31 * result + transportDestination;
                    break;
                case TransportSource:
                    result = 31 * result + transportSource;
                    break;
                case InputPortNumber:
                    result = 31 * result + inputPortNumber;
                    break;
                case NetworkDestination:
                    result = 31 * result + networkDestination.hashCode();
                    break;
                case NetworkSource:
                    result = 31 * result + networkSource.hashCode();
                    break;
                case NetworkProtocol:
                    result = 31 * result + networkProtocol;
                    break;
                case NetworkTTL:
                    result = 31 * result + networkTTL;
                    break;
                case TunnelID:
                    result = 31 * result + (int)(tunnelID ^ tunnelID >>> 32);
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
        switch (networkProtocol) {
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
            default: return Byte.toString(networkProtocol);
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

                case EthernetDestination:
                    str.append("eth_dst=");
                    str.append(ethernetDestination.toString());
                    break;

                case EthernetSource:
                    str.append("eth_src=");
                    str.append(ethernetSource.toString());
                    break;

                case TransportDestination:
                    str.append("transport_dst=");
                    str.append(transportDestination);
                    break;

                case TransportSource:
                    str.append("transport_src=");
                    str.append(transportSource);
                    break;

                case InputPortNumber:
                    str.append("inport=");
                    str.append(inputPortNumber);
                    break;

                case NetworkDestination:
                    str.append("nw_dst=");
                    str.append(networkDestination.toString());
                    break;

                case NetworkSource:
                    str.append("nw_src=");
                    str.append(networkSource.toString());
                    break;

                case NetworkProtocol:
                    str.append("nw_proto=");
                    str.append(getNetworkProtocolAsString());
                    break;

                case NetworkTTL:
                    str.append("nw_ttl=");
                    str.append(networkTTL);
                    break;

                case TunnelID:
                    str.append("tunnel_id=");
                    str.append(tunnelID);
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

                    case EthernetDestination:
                        newClone.ethernetDestination = ethernetDestination;
                        break;

                    case EthernetSource:
                        newClone.ethernetSource = ethernetSource;
                        break;

                    case TransportDestination:
                        newClone.transportDestination = transportDestination;
                        break;

                    case TransportSource:
                        newClone.transportSource = transportSource;
                        break;

                    case InputPortNumber:
                        newClone.inputPortNumber = inputPortNumber;
                        break;

                    case NetworkDestination:
                        newClone.networkDestination = networkDestination;
                        break;

                    case NetworkSource:
                        newClone.networkSource = networkSource;
                        break;

                    case NetworkProtocol:
                        newClone.networkProtocol = networkProtocol;
                        break;

                    case NetworkTOS:
                        newClone.networkTOS = networkTOS;
                        break;

                    case NetworkTTL:
                        newClone.networkTTL = networkTTL;
                        break;

                    case TunnelID:
                        newClone.tunnelID = tunnelID;
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
                    setEthernetSource(MAC.fromAddress(ethernet.getSrc()));
                    setEthernetDestination(MAC.fromAddress(ethernet.getDst()));
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
                    setNetworkSource(
                        IPv4Addr.fromInt(ipv4.getSrc()));
                    setNetworkDestination(
                        IPv4Addr.fromInt(ipv4.getDst()));
                    setNetworkProtocol(ipv4.getProto());
                    setIpFragmentType(IPFragmentType.fromByte(ipv4.getFrag()));
                    setNetworkTTL(ipv4.getTtl());
                    break;

                case OpenVSwitch.FlowKey.Attr.IPv6:
                    FlowKeyIPv6 ipv6 = as(flowKey, FlowKeyIPv6.class);
                    int[] intSrc = ipv6.getSrc();
                    int[] intDst = ipv6.getDst();
                    setNetworkSource(new IPv6Addr(
                        (((long)intSrc[0]) << 32) | (intSrc[1] & 0xFFFFFFFFL),
                        (((long)intSrc[2]) << 32) | (intSrc[3] & 0xFFFFFFFFL)));
                    setNetworkDestination(new IPv6Addr(
                        (((long)intDst[0]) << 32) | (intDst[1] & 0xFFFFFFFFL),
                        (((long)intDst[2]) << 32) | (intDst[3] & 0xFFFFFFFFL)));
                    setNetworkProtocol(ipv6.getProto());
                    setIpFragmentType(ipv6.getFrag());
                    setNetworkTTL(ipv6.getHLimit());
                    break;

                case OpenVSwitch.FlowKey.Attr.TCP:
                    FlowKeyTCP tcp = as(flowKey, FlowKeyTCP.class);
                    setTransportSource(tcp.getSrc());
                    setTransportDestination(tcp.getDst());
                    setNetworkProtocol(TCP.PROTOCOL_NUMBER);
                    break;

                case OpenVSwitch.FlowKey.Attr.UDP:
                    FlowKeyUDP udp = as(flowKey, FlowKeyUDP.class);
                    setTransportSource(udp.getUdpSrc());
                    setTransportDestination(udp.getUdpDst());
                    setNetworkProtocol(UDP.PROTOCOL_NUMBER);
                    break;

                case OpenVSwitch.FlowKey.Attr.ICMP:
                    FlowKeyICMP icmp = as(flowKey, FlowKeyICMP.class);
                    setTransportSource(icmp.getType());
                    setTransportDestination(icmp.getCode());
                    if (icmp instanceof FlowKeyICMPEcho) {
                        FlowKeyICMPEcho icmpEcho = ((FlowKeyICMPEcho) icmp);
                        setIcmpIdentifier(icmpEcho.getIdentifier());
                    } else if (icmp instanceof FlowKeyICMPError) {
                        setIcmpData(((FlowKeyICMPError) icmp).getIcmpData());
                    }
                    setNetworkProtocol(ICMP.PROTOCOL_NUMBER);
                    break;

                case OpenVSwitch.FlowKey.Attr.ICMPv6:
                    // XXX(jlm, s3wong)
                    break;

                case OpenVSwitch.FlowKey.Attr.ARP:
                    FlowKeyARP arp = as(flowKey, FlowKeyARP.class);
                    setNetworkSource(IPv4Addr.fromInt(arp.getSip()));
                    setNetworkDestination(IPv4Addr.fromInt(arp.getTip()));
                    setEtherType(ARP.ETHERTYPE);
                    setNetworkProtocol((byte)(arp.getOp() & 0xff));
                    break;

                case OpenVSwitch.FlowKey.Attr.ND:
                    // XXX(jlm, s3wong): Neighbor Discovery
                    break;

                case OpenVSwitch.FlowKey.Attr.Tunnel_N:
                    // since ovs 1.9, required for ovs 1.10+
                    // matched in "nested" flagged type id:
                    // FlowKeyAttr<FlowKeyTunnel> tun = attrNest(16); ( neq 16 )
                    setTunnelID(as(flowKey, FlowKeyTunnel.class).getTunnelID());
                    break;
            }
        }
    }

    private static <Key extends FlowKey> Key as(FlowKey flowKey,
                                                Class<Key> type) {
        return type.cast(flowKey);
    }
}
