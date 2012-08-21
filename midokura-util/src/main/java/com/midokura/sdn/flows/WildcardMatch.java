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
        InputPortID,
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
    private UUID inputPortID;
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
        usedFields.add(Field.InputPortID);
        this.inputPortID = inputPortID;
        return this;
    }

    @Nullable
    public UUID getInputPortID() {
        return inputPortID;
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
        if (o == null || getClass() != o.getClass()) return false;

        WildcardMatch that = (WildcardMatch) o;

        if (etherType != null ? !etherType.equals(
            that.etherType) : that.etherType != null) return false;
        if (ethernetDestination != null ? !ethernetDestination.equals(
            that.ethernetDestination) : that.ethernetDestination != null)
            return false;
        if (ethernetSource != null ? !ethernetSource.equals(
            that.ethernetSource) : that.ethernetSource != null) return false;
        if (inputPortID != null ? !inputPortID.equals(
            that.inputPortID) : that.inputPortID != null) return false;
        if (inputPortNumber != null ? !inputPortNumber.equals(
            that.inputPortNumber) : that.inputPortNumber != null) return false;
        if (isIPv4Fragment != null ? !isIPv4Fragment.equals(
            that.isIPv4Fragment) : that.isIPv4Fragment != null) return false;
        if (networkDestination != null ? !networkDestination.equals(
            that.networkDestination) : that.networkDestination != null)
            return false;
        if (networkProtocol != null ? !networkProtocol.equals(
            that.networkProtocol) : that.networkProtocol != null) return false;
        if (networkSource != null ? !networkSource.equals(
            that.networkSource) : that.networkSource != null) return false;
        if (transportDestination != null ? !transportDestination.equals(
            that.transportDestination) : that.transportDestination != null)
            return false;
        if (transportSource != null ? !transportSource.equals(
            that.transportSource) : that.transportSource != null) return false;
        if (tunnelID != null ? !tunnelID.equals(
            that.tunnelID) : that.tunnelID != null) return false;
        if (usedFields != null ? !usedFields.equals(
            that.usedFields) : that.usedFields != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = usedFields != null ? usedFields.hashCode() : 0;
        result = 31 * result +
            (inputPortNumber != null ? inputPortNumber.hashCode() : 0);
        result = 31 * result +
            (inputPortID != null ? inputPortID.hashCode() : 0);
        result = 31 * result +
            (tunnelID != null ? tunnelID.hashCode() : 0);
        result = 31 * result +
            (ethernetSource != null ? ethernetSource.hashCode() : 0);
        result = 31 * result +
            (ethernetDestination != null ? ethernetDestination.hashCode() : 0);
        result = 31 * result +
            (etherType != null ? etherType.hashCode() : 0);
        result = 31 * result +
            (networkSource != null ? networkSource.hashCode() : 0);
        result = 31 * result +
            (networkDestination != null ? networkDestination.hashCode() : 0);
        result = 31 * result +
            (networkProtocol != null ? networkProtocol.hashCode() : 0);
        result = 31 * result +
            (isIPv4Fragment != null ? isIPv4Fragment.hashCode() : 0);
        result = 31 * result +
            (transportSource != null ? transportSource.hashCode() : 0);
        result = 31 * result +
            (transportDestination != null ? transportDestination.hashCode() : 0);
        return result;
    }
}
