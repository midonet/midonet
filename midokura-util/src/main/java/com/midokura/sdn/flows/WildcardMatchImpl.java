/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.sdn.flows;

import java.util.EnumSet;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;

public class WildcardMatchImpl implements WildcardMatch<WildcardMatchImpl> {

    EnumSet<Field> fields = EnumSet.noneOf(Field.class);

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

    @Override
    @Nonnull
    public EnumSet<Field> getUsedFields() {
        return fields;
    }

    @Override
    public WildcardMatchImpl setInputPortNumber(short inputPortNumber) {
        fields.add(Field.InputPortNumber);
        this.inputPortNumber = inputPortNumber;
        return this;
    }

    @Override
    @Nullable
    public Short getInputPortNumber() {
        return inputPortNumber;
    }

    @Override
    public WildcardMatchImpl setInputPortUUID(@Nonnull UUID inputPortID) {
        fields.add(Field.InputPortID);
        this.inputPortID = inputPortID;
        return this;
    }

    @Override
    public UUID getInputPortID() {
        return inputPortID;
    }

    @Override
    public WildcardMatchImpl setTunnelID(long tunnelID) {
        this.tunnelID = tunnelID;
        fields.add(Field.TunnelID);
        return this;
    }

    @Override
    public Long getTunnelID() {
        return tunnelID;
    }

    @Override
    public WildcardMatchImpl setEthernetSource(@Nonnull MAC addr) {
        fields.add(Field.EthernetSource);
        this.ethernetSource = addr;
        return this;
    }

    @Override
    public MAC getEthernetSource() {
        return this.ethernetSource;
    }

    @Override
    public WildcardMatchImpl setEthernetDestination(@Nonnull MAC addr) {
        fields.add(Field.EthernetDestination);
        this.ethernetDestination = addr;
        return this;
    }

    @Override
    public MAC getEthernetDestination() {
        return ethernetDestination;
    }

    @Override
    public WildcardMatchImpl setEtherType(short etherType) {
        fields.add(Field.EtherType);
        this.etherType = etherType;
        return this;
    }

    @Override
    public Short getEtherType() {
        return etherType;
    }

    @Override
    public WildcardMatchImpl setNetworkSource(@Nonnull IntIPv4 addr) {
        fields.add(Field.NetworkSource);
        this.networkSource = addr;
        return this;
    }

    @Override
    public IntIPv4 getNetworkSource() {
        return networkSource;
    }

    @Override
    public WildcardMatchImpl setNetworkDestination(@Nonnull IntIPv4 addr) {
        fields.add(Field.NetworkDestination);
        this.networkDestination = addr;
        return this;
    }

    @Override
    public IntIPv4 getNetworkDestination() {
        return networkDestination;
    }

    @Override
    public WildcardMatchImpl setNetworkProtocol(byte networkProtocol) {
        fields.add(Field.NetworkProtocol);
        this.networkProtocol = networkProtocol;
        return this;
    }

    @Override
    public Byte getNetworkProtocol() {
        return networkProtocol;
    }

    @Override
    public WildcardMatchImpl setIsIPv4Fragment(boolean isFragment) {
        fields.add(Field.IsIPv4Fragment);
        this.isIPv4Fragment = isFragment;
        return this;
    }

    @Override
    public Boolean getIsIPv4Fragment() {
        return isIPv4Fragment;
    }

    @Override
    public WildcardMatchImpl setTransportSource(short transportSource) {
        fields.add(Field.TransportSource);
        this.transportSource = transportSource;
        return this;
    }

    @Override
    public Short getTransportSource() {
        return transportSource;
    }

    @Override
    public WildcardMatchImpl setTransportDestination(short transportDestination) {
        fields.add(Field.TransportDestination);
        this.transportDestination = transportDestination;
        return this;
    }

    @Override
    public Short getTransportDestination() {
        return transportDestination;
    }
}
