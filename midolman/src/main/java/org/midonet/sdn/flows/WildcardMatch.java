/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.sdn.flows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.midonet.odp.FlowMatch;
import org.midonet.odp.FlowMatches;
import org.midonet.odp.flows.*;
import org.midonet.packets.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class WildcardMatch implements Cloneable {

    private EnumSet<Field> usedFields = EnumSet.noneOf(Field.class);

    public enum Field {
        InputPortNumber,
        InputPortUUID,
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
        // TODO (galo) extract MM-custom fields to a child class? We'd need
        // some more changes there since Enums can't inherit though
        IcmpId,
        IcmpData,
        VlanId
    }

    /**
     * @return the set of Fields that have been set in this instance.
     */
    @Nonnull
    public Set<Field> getUsedFields() {
        return usedFields;
    }

    private short inputPortNumber = 0;
    private UUID inputPortUUID;
    private long tunnelID = 0L;
    private MAC ethernetSource;
    private MAC ethernetDestination;
    private short etherType = 0;
    private IPAddr networkSource;
    private IPAddr networkDestination;
    private byte networkProtocol = 0;
    private byte networkTTL = 0;
    private byte networkTOS = 0;
    private IPFragmentType ipFragmentType;
    private int transportSource = 0;
    private int transportDestination = 0;

    // Extended fields only supported inside MM
    private short icmpId = 0;
    private byte[] icmpData;
    private List<Short> vlanIds = new ArrayList<Short>();

    public void reset(WildcardMatch that) {
        inputPortNumber = that.inputPortNumber;
        inputPortUUID = that.inputPortUUID;
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
        vlanIds = new ArrayList<Short>(that.vlanIds);
        if (that.icmpData != null)
            this.setIcmpData(that.icmpData);
        else
            this.icmpData = null;
        usedFields.clear();
        usedFields.addAll(that.usedFields);
    }

    public void clear() {
        this.usedFields.clear();
        this.icmpData = null;
        this.networkSource = null;
        this.networkDestination = null;
        this.ethernetSource = null;
        this.ethernetDestination = null;
        this.inputPortUUID = null;
        this.vlanIds = null;
    }

    @Nonnull
    public WildcardMatch setInputPortNumber(short inputPortNumber) {
        usedFields.add(Field.InputPortNumber);
        this.inputPortNumber = inputPortNumber;
        return this;
    }

    @Nonnull
    public WildcardMatch setInputPort(short inputPortNumber) {
        return setInputPortNumber(inputPortNumber);
    }

    @Nullable
    public Short getInputPort() {
        return getInputPortNumber();
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
    public WildcardMatch setInputPortUUID(@Nonnull UUID inputPortID) {
        usedFields.add(Field.InputPortUUID);
        this.inputPortUUID = inputPortID;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetInputPortUUID() {
        usedFields.remove(Field.InputPortUUID);
        this.inputPortUUID = null;
        return this;
    }

    @Nullable
    public UUID getInputPortUUID() {
        return inputPortUUID;
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
    public Long getTunnelID() {
        return usedFields.contains(Field.TunnelID) ? tunnelID : null;
    }


    @Nonnull
    public WildcardMatch setEthernetSource(@Nonnull MAC addr) {
        usedFields.add(Field.EthernetSource);
        this.ethernetSource = addr;
        return this;
    }

    @Deprecated
    @Nonnull
    public WildcardMatch setDataLayerSource(@Nonnull String macaddr) {
        return setDataLayerSource(MAC.fromString(macaddr));
    }

    @Deprecated
    @Nonnull
    public WildcardMatch setDataLayerSource(@Nonnull MAC addr) {
        return setEthernetSource(addr);
    }

    @Nonnull
    public WildcardMatch unsetEthernetSource() {
        usedFields.remove(Field.EthernetSource);
        this.ethernetSource = null;
        return this;
    }

    @Nullable
    public MAC getEthernetSource() {
        return ethernetSource;
    }

    @Deprecated
    @Nullable
    public byte[] getDataLayerSource() {
        return ethernetSource.getAddress();
    }

    @Nonnull
    public WildcardMatch setEthernetDestination(@Nonnull MAC addr) {
        usedFields.add(Field.EthernetDestination);
        this.ethernetDestination = addr;
        return this;
    }

    @Deprecated
    @Nonnull
    public WildcardMatch setDataLayerDestination(@Nonnull String macAddr) {
        return setDataLayerDestination(MAC.fromString(macAddr));
    }

    @Deprecated
    @Nonnull
    public WildcardMatch setDataLayerDestination(@Nonnull MAC addr) {
        return setEthernetDestination(addr);
    }

    @Nonnull
    public WildcardMatch unsetEthernetDestination() {
        usedFields.remove(Field.EthernetDestination);
        this.ethernetDestination = null;
        return this;
    }

    @Nullable
    public MAC getEthernetDestination() {
        return ethernetDestination;
    }

    @Deprecated
    @Nullable
    public byte[] getDataLayerDestination() {
        return ethernetDestination.getAddress();
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
        return usedFields.contains(Field.EtherType) ? etherType : null;
    }

    @Deprecated
    @Nonnull
    public WildcardMatch setDataLayerType(short dlType) {
        return setEtherType(dlType);
    }

    @Deprecated
    public short getDataLayerType() {
        return usedFields.contains(Field.EtherType) ? etherType : 0;
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
        return networkSource;
    }

    /**
     *
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
    public Byte getNetworkProtocolObject() {
        return usedFields.contains(Field.NetworkProtocol) ? networkProtocol : null;
    }

    @Deprecated
    public byte getNetworkProtocol() {
        return networkProtocol;
    }

    @Nullable
    public Byte getNetworkTOS() {
        return usedFields.contains(Field.NetworkTOS) ? networkTOS : null;
    }

    @Deprecated
    public byte getNetworkTypeOfService() {
        return usedFields.contains(Field.NetworkTOS) ? networkTOS : 0;
    }

    @Nonnull
    public WildcardMatch setNetworkTOS(byte tos) {
        usedFields.add(Field.NetworkTOS);
        this.networkTOS = tos;
        return this;
    }

    @Deprecated
    @Nonnull
    public WildcardMatch setNetworkTypeOfService(byte tos) {
        return setNetworkTOS(tos);
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
        return ipFragmentType;
    }

    @Nonnull
    public WildcardMatch setTransportSource(int transportSource) {
        if (transportSource < 0 || transportSource > 65535)
            throw new IllegalArgumentException(
                    "Transport source port out of range");

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
    public Integer getTransportSourceObject() {
        return usedFields.contains(Field.TransportSource) ? transportSource : null;
    }

    @Deprecated
    public int getTransportSource() {
        return transportSource;
    }

    @Nonnull
    public WildcardMatch setTransportDestination(int transportDestination) {
        if (transportDestination < 0 || transportDestination > 65535)
            throw new IllegalArgumentException(
                    "Transport destination port out of range");

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
    public Integer getTransportDestinationObject() {
        return usedFields.contains(Field.TransportDestination) ?
            transportDestination : null;
    }

    @Deprecated
    public int getTransportDestination() {
        return transportDestination;
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
        return usedFields.contains(Field.IcmpId) ? icmpId : null;
    }

    @Nullable
    public byte[] getIcmpData() {
        return (icmpData == null) ? null
                                  : Arrays.copyOf(icmpData, icmpData.length);
    }

    @Nonnull
    public WildcardMatch setIcmpData(byte[] icmpData) {
        usedFields.add(Field.IcmpData);
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
        return this;
    }

    @Nullable
    public List<Short> getVlanIds() {
        return vlanIds;
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

                case InputPortUUID:
                    if (!isEqual(field, that, inputPortUUID, that.inputPortUUID))
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
                    int thisHash = icmpData != null ? icmpData.hashCode(): 0;
                    int thatHash = that.icmpData != null ? that.icmpData.hashCode(): 0;
                    if (!isEqual(field, that, thisHash, thatHash))
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
                case InputPortUUID:
                    result = 31 * result + inputPortUUID.hashCode();
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
                case IcmpData:
                    result = 31 * result + Arrays.hashCode(icmpData);
                case VlanId:
                    result = 31 * result + vlanIds.hashCode();
            }
        }

        return result;
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        for (Field f: getUsedFields()) {
            str.append(f.toString() + " : ");
            switch (f) {
                case EtherType:
                    str.append(etherType);
                    break;

                case FragmentType:
                    str.append(ipFragmentType.toString());
                    break;

                case EthernetDestination:
                    str.append(ethernetDestination.toString());
                    break;

                case EthernetSource:
                    str.append(ethernetSource.toString());
                    break;

                case TransportDestination:
                    str.append(transportDestination);
                    break;

                case TransportSource:
                    str.append(transportSource);
                    break;

                case InputPortUUID:
                    str.append(inputPortUUID.toString());
                    break;

                case InputPortNumber:
                    str.append(inputPortNumber);
                    break;

                case NetworkDestination:
                    str.append(networkDestination.toString());
                    break;

                case NetworkSource:
                    str.append(networkSource.toString());
                    break;

                case NetworkProtocol:
                    str.append(networkProtocol);
                    break;

                case NetworkTTL:
                    str.append(networkTTL);
                    break;

                case TunnelID:
                    str.append(tunnelID);
                    break;
                case IcmpId:
                    str.append(icmpId);
                    break;

                case IcmpData:
                    str.append(Arrays.toString(icmpData));

                case VlanId:
                    str.append(vlanIds);
            }
            str.append(";");
        }
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
                        newClone.ethernetDestination =
                            ethernetDestination.clone();
                        break;

                    case EthernetSource:
                        newClone.ethernetSource = ethernetSource.clone();
                        break;

                    case TransportDestination:
                        newClone.transportDestination = transportDestination;
                        break;

                    case TransportSource:
                        newClone.transportSource = transportSource;
                        break;

                    case InputPortUUID:
                        newClone.inputPortUUID = inputPortUUID;
                        break;

                    case InputPortNumber:
                        newClone.inputPortNumber = inputPortNumber;
                        break;

                    case NetworkDestination:
                        newClone.networkDestination =
                            (IPAddr)networkDestination.copy();
                        break;

                    case NetworkSource:
                        newClone.networkSource = (IPAddr)networkSource.copy();
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
        WildcardMatch wildcardMatch = new WildcardMatch();
        List<FlowKey<?>> flowKeys = match.getKeys();
        wildcardMatch.processMatchKeys(flowKeys);

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

    private void processMatchKeys(List<FlowKey<?>> flowKeys) {
        for (FlowKey<?> flowKey : flowKeys) {
            switch (flowKey.getKey().getId()) {
                case 1: // FlowKeyAttr<FlowKeyEncap> ENCAP = attr(1);
                    FlowKeyEncap encap = as(flowKey, FlowKeyEncap.class);
                    processMatchKeys(encap.getKeys());
                    break;
                case 2: // FlowKeyAttr<FlowKeyPriority> PRIORITY = attr(2);
                    // TODO(pino)
                    break;
                case 3: // FlowKeyAttr<FlowKeyInPort> IN_PORT = attr(3);
                    FlowKeyInPort inPort = as(flowKey, FlowKeyInPort.class);
                    setInputPortNumber((short) inPort.getInPort());
                    break;
                case 4: // FlowKeyAttr<FlowKeyEthernet> ETHERNET = attr(4);
                    FlowKeyEthernet ethernet = as(flowKey,
                                                  FlowKeyEthernet.class);
                    setEthernetSource(MAC.fromAddress(ethernet.getSrc()));
                    setEthernetDestination(MAC.fromAddress(ethernet.getDst()));
                    break;
                case 5: // FlowKeyAttr<FlowKeyVLAN> VLAN = attr(5);
                    FlowKeyVLAN vlan = as(flowKey, FlowKeyVLAN.class);
                    addVlanId(vlan.getVLAN());
                    break;
                case 6: // FlowKeyAttr<FlowKeyEtherType> ETHERTYPE = attr(6);
                    FlowKeyEtherType etherType = as(flowKey,
                                                    FlowKeyEtherType.class);
                    setEtherType(etherType.getEtherType());
                    break;
                case 7: // FlowKeyAttr<FlowKeyIPv4> IPv4 = attr(7);
                    FlowKeyIPv4 ipv4 = as(flowKey, FlowKeyIPv4.class);
                    setNetworkSource(
                        IPv4Addr.fromInt(ipv4.getSrc()));
                    setNetworkDestination(
                        IPv4Addr.fromInt(ipv4.getDst()));
                    setNetworkProtocol(ipv4.getProto());
                    setIpFragmentType(IPFragmentType.fromByte(ipv4.getFrag()));
                    setNetworkTTL(ipv4.getTtl());
                    break;
                case 8: // FlowKeyAttr<FlowKeyIPv6> IPv6 = attr(8);
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
                case 9: //FlowKeyAttr<FlowKeyTCP> TCP = attr(9);
                    FlowKeyTCP tcp = as(flowKey, FlowKeyTCP.class);
                    setTransportSource(tcp.getSrc());
                    setTransportDestination(tcp.getDst());
                    setNetworkProtocol(TCP.PROTOCOL_NUMBER);
                    break;
                case 10: // FlowKeyAttr<FlowKeyUDP> UDP = attr(10);
                    FlowKeyUDP udp = as(flowKey, FlowKeyUDP.class);
                    setTransportSource(udp.getUdpSrc());
                    setTransportDestination(udp.getUdpDst());
                    setNetworkProtocol(UDP.PROTOCOL_NUMBER);
                    break;
                case 11: // FlowKeyAttr<FlowKeyICMP> ICMP = attr(11);
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
                case 12: // FlowKeyAttr<FlowKeyICMPv6> ICMPv6 = attr(12);
                    // XXX(jlm, s3wong)
                    break;
                case 13: // FlowKeyAttr<FlowKeyARP> ARP = attr(13);
                    FlowKeyARP arp = as(flowKey, FlowKeyARP.class);
                    setNetworkSource(IPv4Addr.fromInt(arp.getSip()));
                    setNetworkDestination(IPv4Addr.fromInt(arp.getTip()));
                    setEtherType(ARP.ETHERTYPE);
                    setNetworkProtocol((byte)(arp.getOp() & 0xff));
                    break;
                case 14: // FlowKeyAttr<FlowKeyND> ND = attr(14);
                    // XXX(jlm, s3wong): Neighbor Discovery
                    break;
                case 63: // FlowKeyAttr<FlowKeyTunnelID> TUN_ID = attr(63);
                    FlowKeyTunnelID tunnelID = as(flowKey,
                                                  FlowKeyTunnelID.class);
                    setTunnelID(tunnelID.getTunnelID());
                    break;
            }
        }
    }

    private static <Key extends FlowKey<Key>>
                   Key as(FlowKey<?> flowKey, Class<Key> type) {
        return type.cast(flowKey.getValue());
    }

}
