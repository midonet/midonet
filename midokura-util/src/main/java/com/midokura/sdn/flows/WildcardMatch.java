/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.sdn.flows;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;

public class WildcardMatch {

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
        IsIPv4Fragment,
        TransportSource,
        TransportDestination
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
    private IntIPv4 networkSource;
    private IntIPv4 networkDestination;
    private Byte networkProtocol;
    private Boolean isIPv4Fragment;
    private Short transportSource;
    private Short transportDestination;

    @Nonnull
    public WildcardMatch setInputPortNumber(short inputPortNumber) {
        usedFields.add(Field.InputPortNumber);
        this.inputPortNumber = inputPortNumber;
        return this;
    }

    @Nullable
    public Short getInputPortNumber() {
        return inputPortNumber;
    }

    public WildcardMatch setInputPortUUID(@Nonnull UUID inputPortID) {
        usedFields.add(Field.InputPortUUID);
        this.inputPortUUID = inputPortID;
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

    @Nullable
    public MAC getEthernetSource() {
        return ethernetSource;
    }

    @Nonnull
    public WildcardMatch setEthernetDestination(@Nonnull MAC addr) {
        usedFields.add(Field.EthernetDestination);
        this.ethernetDestination = addr;
        return this;
    }

    @Nullable
    public MAC getEthernetDestination() {
        return ethernetDestination;
    }

    @Nonnull
    public WildcardMatch setEtherType(short etherType) {
        usedFields.add(Field.EtherType);
        this.etherType = etherType;
        return this;
    }

    @Nullable
    public Short getEtherType() {
        return etherType;
    }

    @Nonnull
    public WildcardMatch setNetworkSource(@Nonnull IntIPv4 addr) {
        usedFields.add(Field.NetworkSource);
        this.networkSource = addr;
        return this;
    }

    @Nullable
    public IntIPv4 getNetworkSource() {
        return networkSource;
    }

    @Nonnull
    public WildcardMatch setNetworkDestination(@Nonnull IntIPv4 addr) {
        usedFields.add(Field.NetworkDestination);
        this.networkDestination = addr;
        return this;
    }

    @Nullable
    public IntIPv4 getNetworkDestination() {
        return networkDestination;
    }

    @Nonnull
    public WildcardMatch setNetworkProtocol(byte networkProtocol) {
        usedFields.add(Field.NetworkProtocol);
        this.networkProtocol = networkProtocol;
        return this;
    }

    @Nullable
    public Byte getNetworkProtocol() {
        return networkProtocol;
    }

    @Nonnull
    public WildcardMatch setIsIPv4Fragment(boolean isFragment) {
        usedFields.add(Field.IsIPv4Fragment);
        this.isIPv4Fragment = isFragment;
        return this;
    }

    @Nullable
    public Boolean getIsIPv4Fragment() {
        return isIPv4Fragment;
    }

    @Nonnull
    public WildcardMatch setTransportSource(short transportSource) {
        usedFields.add(Field.TransportSource);
        this.transportSource = transportSource;
        return this;
    }

    @Nullable
    public Short getTransportSource() {
        return transportSource;
    }

    @Nonnull
    public WildcardMatch setTransportDestination(short transportDestination) {
        usedFields.add(Field.TransportDestination);
        this.transportDestination = transportDestination;
        return this;
    }

    @Nullable
    public Short getTransportDestination() {
        return transportDestination;
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

                case IsIPv4Fragment:
                    if (!isEqual(isIPv4Fragment, that.isIPv4Fragment))
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

                case TunnelID:
                    if (!isEqual(tunnelID, that.tunnelID))
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
                case IsIPv4Fragment:
                    result = 31 * result + isIPv4Fragment.hashCode();
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
                case TunnelID:
                    result = 31 * result + tunnelID.hashCode();
                    break;
            }
        }

        return result;
    }
}
