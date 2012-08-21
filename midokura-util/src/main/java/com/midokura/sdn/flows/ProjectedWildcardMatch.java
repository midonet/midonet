/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.sdn.flows;

import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;

public class ProjectedWildcardMatch extends WildcardMatch {

    private final Set<Field> fields;
    private final WildcardMatch source;

    public ProjectedWildcardMatch(Set<Field> fields, WildcardMatch source) {
        this.fields = fields;
        this.source = source;
    }

    @Override
    @Nonnull
    public Set<Field> getUsedFields() {
        return fields;
    }

    @Override
    @Nonnull
    public ProjectedWildcardMatch setInputPortNumber(short inputPortNumber) {
        if (fields.contains(Field.InputPortNumber))
            source.setInputPortNumber(inputPortNumber);

        return this;
    }

    public Short getInputPortNumber() {
        return
            fields.contains(Field.InputPortNumber)
                ? source.getInputPortNumber() : null;
    }

    public ProjectedWildcardMatch setInputPortUUID(@Nonnull UUID inputPortID) {
        if (fields.contains(Field.InputPortID))
            source.setInputPortUUID(inputPortID);

        return this;
    }

    @Override
    @Nullable
    public UUID getInputPortID() {
        return
            fields.contains(Field.InputPortID)
                ? source.getInputPortID() : null;
    }

    @Nonnull
    @Override
    public ProjectedWildcardMatch setTunnelID(long tunnelID) {
        if (fields.contains(Field.TunnelID))
            source.setTunnelID(tunnelID);

        return this;
    }

    @Override
    @Nullable
    public Long getTunnelID() {
        return
            fields.contains(Field.TunnelID)
                ? source.getTunnelID() : null;
    }

    @Nonnull
    @Override
    public ProjectedWildcardMatch setEthernetSource(@Nonnull MAC addr) {
        if (fields.contains(Field.EthernetSource))
            source.setEthernetSource(addr);

        return this;
    }

    @Override
    @Nullable
    public MAC getEthernetSource() {
        return
            fields.contains(Field.EthernetSource)
                ? source.getEthernetSource() : null;
    }

    @Override
    @Nonnull
    public ProjectedWildcardMatch setEthernetDestination(@Nonnull MAC addr) {
        if (fields.contains(Field.EthernetDestination))
            source.setEthernetDestination(addr);

        return this;
    }

    @Override
    public MAC getEthernetDestination() {
        return
            fields.contains(Field.EthernetDestination)
                ? source.getEthernetDestination() : null;
    }

    @Nonnull
    @Override
    public ProjectedWildcardMatch setEtherType(short etherType) {
        if (fields.contains(Field.EtherType))
            source.setEtherType(etherType);

        return this;
    }

    @Override
    public Short getEtherType() {
        return
            fields.contains(Field.EtherType)
                ? source.getEtherType() : null;
    }

    @Nonnull
    @Override
    public ProjectedWildcardMatch setNetworkSource(@Nonnull IntIPv4 addr) {
        if (fields.contains(Field.NetworkSource))
            source.setNetworkSource(addr);

        return this;
    }

    @Override
    @Nullable
    public IntIPv4 getNetworkSource() {
        return
            fields.contains(Field.NetworkSource)
                ? source.getNetworkSource() : null;
    }

    @Nonnull
    @Override
    public ProjectedWildcardMatch setNetworkDestination(@Nonnull IntIPv4 addr) {
        if (fields.contains(Field.NetworkDestination))
            source.setNetworkDestination(addr);

        return this;
    }

    @Override
    @Nullable
    public IntIPv4 getNetworkDestination() {
        return
            fields.contains(Field.NetworkDestination)
                ? source.getNetworkDestination() : null;
    }

    @Override
    @Nonnull
    public ProjectedWildcardMatch setNetworkProtocol(byte networkProtocol) {
        if (fields.contains(Field.NetworkProtocol))
            source.setNetworkProtocol(networkProtocol);

        return this;
    }

    @Override
    @Nullable
    public Byte getNetworkProtocol() {
        return
            fields.contains(Field.NetworkProtocol)
                ? source.getNetworkProtocol() : null;
    }

    @Nonnull
    @Override
    public ProjectedWildcardMatch setIsIPv4Fragment(boolean isFragment) {
        if (fields.contains(Field.IsIPv4Fragment))
            source.setIsIPv4Fragment(isFragment);

        return this;
    }

    @Override
    @Nullable
    public Boolean getIsIPv4Fragment() {
        return
            fields.contains(Field.IsIPv4Fragment)
                ? source.getIsIPv4Fragment() : null;
    }

    @Override
    @Nonnull
    public ProjectedWildcardMatch setTransportSource(short transportSource) {
        if (fields.contains(Field.TransportSource))
            source.setTransportSource(transportSource);

        return this;
    }


    @Override
    @Nullable
    public Short getTransportSource() {
        return
            fields.contains(Field.TransportSource)
                ? source.getTransportSource() : null;
    }

    @Nonnull
    @Override
    public ProjectedWildcardMatch setTransportDestination(short transportDestination) {
        if (fields.contains(Field.TransportDestination))
            source.setTransportDestination(transportDestination);

        return this;
    }

    @Override
    @Nullable
    public Short getTransportDestination() {
        return
        fields.contains(Field.TransportDestination)
                ? source.getTransportDestination() : null;
    }

    @Nonnull
    public WildcardMatch getSource() {
        return source;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof WildcardMatch)) return false;

        WildcardMatch that = (WildcardMatch) o;

        return hashCode() == that.hashCode();
    }

    @Override
    public int hashCode() {
        int result = fields != null ? fields.hashCode() : 0;

//        for (Field field : fields) {
//            switch (field) {
//                case
//            }
//
//        }
//        int result = usedFields != null ? usedFields.hashCode() : 0;
//        result = 31 * result +
//            (inputPortNumber != null ? inputPortNumber.hashCode() : 0);
//        result = 31 * result +
//            (inputPortID != null ? inputPortID.hashCode() : 0);
//        result = 31 * result +
//            (tunnelID != null ? tunnelID.hashCode() : 0);
//        result = 31 * result +
//            (ethernetSource != null ? ethernetSource.hashCode() : 0);
//        result = 31 * result +
//            (ethernetDestination != null ? ethernetDestination.hashCode() : 0);
//        result = 31 * result +
//            (etherType != null ? etherType.hashCode() : 0);
//        result = 31 * result +
//            (networkSource != null ? networkSource.hashCode() : 0);
//        result = 31 * result +
//            (networkDestination != null ? networkDestination.hashCode() : 0);
//        result = 31 * result +
//            (networkProtocol != null ? networkProtocol.hashCode() : 0);
//        result = 31 * result +
//            (isIPv4Fragment != null ? isIPv4Fragment.hashCode() : 0);
//        result = 31 * result +
//            (transportSource != null ? transportSource.hashCode() : 0);
//        result = 31 * result +
//            (transportDestination != null ? transportDestination.hashCode() : 0);
//        return result;
//
//        result = 31 * result + (source != null ? source.hashCode() : 0);
        return result;
    }
}
