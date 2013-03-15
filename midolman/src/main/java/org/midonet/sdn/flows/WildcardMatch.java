/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.sdn.flows;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.odp.FlowMatch;
import org.midonet.odp.FlowMatches;
import org.midonet.odp.flows.*;
import org.midonet.packets.ARP;
import org.midonet.packets.Ethernet;
import org.midonet.packets.ICMP;
import org.midonet.packets.IPAddr;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv6Addr;
import org.midonet.packets.MAC;
import org.midonet.packets.MalformedPacketException;
import org.midonet.packets.TCP;
import org.midonet.packets.Transport;
import org.midonet.packets.UDP;


public class WildcardMatch implements Cloneable {

    private static final Logger log =
            LoggerFactory.getLogger(WildcardMatch.class);

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
        ArpSip,
        ArpTip,
        // TODO (galo) extract MM-custom fields to a child class? We'd need
        // some more changes there since Enums can't inherit though
        IcmpId,
        IcmpData
    }

    /**
     * @return the set of Fields that have been set in this instance.
     */
    @Nonnull
    public Set<Field> getUsedFields() {
        return usedFields;
    }

    private Short inputPortNumber;
    private UUID inputPortUUID;
    private Long tunnelID;
    private MAC ethernetSource;
    private MAC ethernetDestination;
    private Short etherType;
    private IPAddr networkSource;
    private IPAddr networkDestination;
    private Byte networkProtocol;
    private Byte networkTTL;
    private Byte networkTOS;
    private IPFragmentType ipFragmentType;
    private Integer transportSource;
    private Integer transportDestination;
    private IPv4Addr arpSip;
    private IPv4Addr arpTip;

    // Extended fields only supported inside MM
    private Short icmpId;
    private byte[] icmpData;

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
        this.inputPortNumber = null;
        return this;
    }

    @Nullable
    public Short getInputPortNumber() {
        return inputPortNumber;
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
        this.tunnelID = null;
        return this;
    }

    @Nullable
    public Long getTunnelID() {
        return tunnelID;
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
        this.etherType = null;
        return this;
    }

    @Nullable
    public Short getEtherType() {
        return etherType;
    }

    @Deprecated
    @Nonnull
    public WildcardMatch setDataLayerType(short dlType) {
        return setEtherType(dlType);
    }

    @Deprecated
    public short getDataLayerType() {
        return etherType.shortValue();
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
        this.networkProtocol = null;
        return this;
    }

    @Nullable
    public Byte getNetworkProtocolObject() {
        return networkProtocol;
    }

    @Deprecated
    public byte getNetworkProtocol() {
        return networkProtocol.byteValue();
    }

    @Nullable
    public Byte getNetworkTOS() {
        return networkTOS;
    }

    @Deprecated
    public byte getNetworkTypeOfService() {
        return (null == networkTOS)? 0 : networkTOS;
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
        this.networkTTL = null;
        return this;
    }

    @Nullable
    public Byte getNetworkTTL() {
        return networkTTL;
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
        this.transportSource = null;
        return this;
    }

    @Nullable
    public Integer getTransportSourceObject() {
        return transportSource;
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
        this.transportDestination = null;
        return this;
    }

    @Nullable
    public Integer getTransportDestinationObject() {
        return transportDestination;
    }

    @Deprecated
    public int getTransportDestination() {
        return transportDestination;
    }

    @Nonnull
    public WildcardMatch setArpSip(IPv4Addr arpSip) {
        usedFields.add(Field.ArpSip);
        this.arpSip = arpSip;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetArpSip() {
        usedFields.remove(Field.ArpSip);
        this.arpSip = null;
        return this;
    }

    @Nullable
    public IPv4Addr getArpSipObject() {
        return arpSip;
    }

    @Nonnull
    public WildcardMatch setArpTip(IPv4Addr arpTip) {
        usedFields.add(Field.ArpTip);
        this.arpTip = arpTip;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetArpTip() {
        usedFields.remove(Field.ArpTip);
        this.arpTip = null;
        return this;
    }

    @Nullable
    public IPv4Addr getArpTipObject() {
        return arpTip;
    }

    @Nonnull
    public WildcardMatch setIcmpIdentifier(Short identifier) {
        usedFields.add(Field.IcmpId);
        this.icmpId = identifier;
        return this;
    }

    @Nonnull
    public WildcardMatch unsetIcmpIdentifier() {
        usedFields.remove(Field.IcmpId);
        this.icmpId = null;
        return this;
    }

    @Nullable
    public Short getIcmpIdentifier() {
        return icmpId;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof WildcardMatch)) return false;

        WildcardMatch that = (WildcardMatch) o;

        for (Field field : getUsedFields()) {
            switch (field) {
                case EtherType:
                    if (!isEqual(etherType, that.etherType))
                        return false;
                    break;

                case FragmentType:
                    if (!isEqual(ipFragmentType, that.ipFragmentType))
                        return false;
                    break;

                case EthernetDestination:
                    if (!isEqual(ethernetDestination, that.ethernetDestination))
                        return false;
                    break;

                case EthernetSource:
                    if (!isEqual(ethernetSource, that.ethernetSource))
                        return false;
                    break;

                case TransportDestination:
                    if (!isEqual(transportDestination, that.transportDestination))
                        return false;
                    break;

                case TransportSource:
                    if (!isEqual(transportSource, that.transportSource))
                        return false;
                    break;

                case ArpSip:
                    if (!isEqual(arpSip, that.arpSip))
                        return false;
                    break;

                case ArpTip:
                    if (!isEqual(arpTip, that.arpTip))
                        return false;
                    break;

                case InputPortUUID:
                    if (!isEqual(inputPortUUID, that.inputPortUUID))
                        return false;
                    break;

                case InputPortNumber:
                    if (!isEqual(inputPortNumber, that.inputPortNumber))
                        return false;
                    break;

                case NetworkDestination:
                    if (!isEqual(networkDestination, that.networkDestination))
                        return false;
                    break;

                case NetworkSource:
                    if (!isEqual(networkSource, that.networkSource))
                        return false;
                    break;

                case NetworkProtocol:
                    if (!isEqual(networkProtocol, that.networkProtocol))
                        return false;
                    break;

                case NetworkTTL:
                    if (!isEqual(networkTTL, that.networkTTL))
                        return false;
                    break;

                case TunnelID:
                    if (!isEqual(tunnelID, that.tunnelID))
                        return false;
                    break;

                case IcmpId:
                    if (!isEqual(icmpId, that.icmpId))
                        return false;
                    break;

                case IcmpData:
                    if (!isEqual(icmpData.hashCode(),  that.icmpData.hashCode()))
                        return false;
                    break;

            }
        }

        return true;
    }

    private <T> boolean isEqual(T a, T b) {
        return a != null ? a.equals(b) : b == null;
    }

    @Override
    public int hashCode() {
        int result = getUsedFields().hashCode();
        for (Field field : getUsedFields()) {
            switch (field) {
                case EtherType:
                    result = 31 * result + etherType.hashCode();
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
                    result = 31 * result + transportDestination.hashCode();
                    break;
                case TransportSource:
                    result = 31 * result + transportSource.hashCode();
                    break;
                case ArpSip:
                    result = 31 * result + arpSip.hashCode();
                    break;
                case ArpTip:
                    result = 31 * result + arpTip.hashCode();
                    break;
                case InputPortUUID:
                    result = 31 * result + inputPortUUID.hashCode();
                    break;
                case InputPortNumber:
                    result = 31 * result + inputPortNumber.hashCode();
                    break;
                case NetworkDestination:
                    result = 31 * result + networkDestination.hashCode();
                    break;
                case NetworkSource:
                    result = 31 * result + networkSource.hashCode();
                    break;
                case NetworkProtocol:
                    result = 31 * result + networkProtocol.hashCode();
                    break;
                case NetworkTTL:
                    result = 31 * result + networkTTL;
                    break;
                case TunnelID:
                    result = 31 * result + tunnelID.hashCode();
                    break;
                case IcmpId:
                    result = 31 * result + icmpId.hashCode();
                case IcmpData:
                    result = 31 * result + Arrays.hashCode(icmpData);
            }
        }

        return result;
    }

    @Override
    public String toString() {
        String output = "";
        for (Field f: getUsedFields()) {
            output += f.toString() + " : ";
            switch (f) {
                case EtherType:
                    output += etherType.toString();
                    break;

                case FragmentType:
                    output += ipFragmentType.toString();
                    break;

                case EthernetDestination:
                    output += ethernetDestination.toString();
                    break;

                case EthernetSource:
                    output += ethernetSource.toString();
                    break;

                case TransportDestination:
                    output += transportDestination.toString();
                    break;

                case TransportSource:
                    output += transportSource.toString();
                    break;

                case InputPortUUID:
                    output += inputPortUUID.toString();
                    break;

                case InputPortNumber:
                    output += inputPortNumber.toString();
                    break;

                case NetworkDestination:
                    output += networkDestination.toString();
                    break;

                case NetworkSource:
                    output += networkSource.toString();
                    break;

                case NetworkProtocol:
                    output += networkProtocol.toString();
                    break;

                case NetworkTTL:
                    output += networkTTL.toString();
                    break;

                case TunnelID:
                    output += tunnelID.toString();
                    break;
                case IcmpId:
                    output += Short.toString(icmpId);
                    break;

                case IcmpData:
                    output += Arrays.toString(icmpData);
            }
            output += ";";
        }
        return output;
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
                        newClone.networkDestination = networkDestination.clone_();
                        break;

                    case NetworkSource:
                        newClone.networkSource = networkSource.clone_();
                        break;

                    case ArpSip:
                        newClone.arpSip = arpSip.clone();
                        break;

                    case ArpTip:
                        newClone.arpTip = arpTip.clone();
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
        return wildcardMatch;
    }

    public static WildcardMatch fromEthernetPacket(Ethernet ethPkt) {
        return fromFlowMatch(FlowMatches.fromEthernetPacket(ethPkt));
    }

    private void processMatchKeys(List<FlowKey<?>> flowKeys) {
        for (FlowKey<?> flowKey : flowKeys) {
            switch (flowKey.getKey().getId()) {
                case 1: // FlowKeyAttr<FlowKeyEncap> ENCAP = attr(1);
                    // TODO(pino)
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
                    setEthernetSource(new MAC(ethernet.getSrc()));
                    setEthernetDestination(new MAC(ethernet.getDst()));
                    break;
                case 5: // FlowKeyAttr<FlowKeyVLAN> VLAN = attr(5);
                    // TODO(pino)
                    break;
                case 6: // FlowKeyAttr<FlowKeyEtherType> ETHERTYPE = attr(6);
                    FlowKeyEtherType etherType = as(flowKey,
                                                    FlowKeyEtherType.class);
                    setEtherType(etherType.getEtherType());
                    break;
                case 7: // FlowKeyAttr<FlowKeyIPv4> IPv4 = attr(7);
                    FlowKeyIPv4 ipv4 = as(flowKey, FlowKeyIPv4.class);
                    setNetworkSource(
                        new IPv4Addr().setIntAddress(ipv4.getSrc()));
                    setNetworkDestination(
                        new IPv4Addr().setIntAddress(ipv4.getDst()));
                    setNetworkProtocol(ipv4.getProto());
                    setIpFragmentType(IPFragmentType.fromByte(ipv4.getFrag()));
                    setNetworkTTL(ipv4.getTtl());
                    break;
                case 8: // FlowKeyAttr<FlowKeyIPv6> IPv6 = attr(8);
                    FlowKeyIPv6 ipv6 = as(flowKey, FlowKeyIPv6.class);
                    int[] intSrc = ipv6.getSrc();
                    int[] intDst = ipv6.getDst();
                    setNetworkSource(new IPv6Addr().setAddress(
                        (((long) intSrc[0]) << 32) | (intSrc[1] & 0xFFFFFFFFL),
                        (((long) intSrc[2]) << 32) | (intSrc[3] & 0xFFFFFFFFL)));
                    setNetworkDestination(new IPv6Addr().setAddress(
                        (((long) intSrc[0]) << 32) | (intSrc[1] & 0xFFFFFFFFL),
                        (((long) intSrc[2]) << 32) | (intSrc[3] & 0xFFFFFFFFL)));
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
                    setNetworkSource(
                        new IPv4Addr().setIntAddress(arp.getSip()));
                    setNetworkDestination(
                        new IPv4Addr().setIntAddress(arp.getTip()));
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
