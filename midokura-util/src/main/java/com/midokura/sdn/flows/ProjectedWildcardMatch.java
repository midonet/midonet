/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.sdn.flows;

import java.util.EnumSet;
import java.util.UUID;
import javax.annotation.Nonnull;

import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;

public class ProjectedWildcardMatch
        implements WildcardMatch<ProjectedWildcardMatch> {

    private final EnumSet<Field> fields;
    private final WildcardMatch<?> source;

    public ProjectedWildcardMatch(EnumSet<Field> fields, WildcardMatch<?> source) {
        this.fields = fields;
        this.source = source;
    }

    @Override
    public MAC getEthernetDestination() {
        if (fields.contains(Field.EthernetDestination))
            return source.getEthernetDestination();

        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public EnumSet<Field> getUsedFields() {
        return fields;
    }

    @Override
    public ProjectedWildcardMatch setInputPortNumber(short inputPortNumber) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Short getInputPortNumber() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public ProjectedWildcardMatch setInputPortUUID(@Nonnull UUID inputPortID) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public UUID getInputPortID() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public ProjectedWildcardMatch setTunnelID(long tunnelID) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Long getTunnelID() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public ProjectedWildcardMatch setEthernetSource(@Nonnull MAC addr) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public MAC getEthernetSource() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public ProjectedWildcardMatch setEthernetDestination(@Nonnull MAC addr) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public ProjectedWildcardMatch setEtherType(short etherType) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Short getEtherType() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public ProjectedWildcardMatch setNetworkSource(@Nonnull IntIPv4 addr) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public IntIPv4 getNetworkSource() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public ProjectedWildcardMatch setNetworkDestination(@Nonnull IntIPv4 addr) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public IntIPv4 getNetworkDestination() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public ProjectedWildcardMatch setNetworkProtocol(byte networkProtocol) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Byte getNetworkProtocol() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public ProjectedWildcardMatch setIsIPv4Fragment(boolean isFragment) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Boolean getIsIPv4Fragment() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public ProjectedWildcardMatch setTransportSource(short transportSource) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Short getTransportSource() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public ProjectedWildcardMatch setTransportDestination(short transportDestination) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Short getTransportDestination() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }


}
