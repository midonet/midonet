/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.sdn.flows;

import java.util.UUID;

import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;

public class WildcardMatchImpl implements MidoMatch<WildcardMatchImpl> {

    @Override
    public WildcardMatchImpl setInputPortNumber(short inputPortNumber) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Short getInputPortNumber() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public WildcardMatchImpl setInputPortUUID(UUID inputPortID) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public UUID getInputPortID() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public WildcardMatchImpl setTunnelID(long tunnelID) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Long getTunnelID() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public WildcardMatchImpl setEthernetSource(MAC addr) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public MAC getEthernetSource() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public WildcardMatchImpl setEthernetDestination(MAC addr) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public MAC getEthernetDestination() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public WildcardMatchImpl setEtherType(short etherType) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Short getEtherType() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public WildcardMatchImpl setNetworkSource(IntIPv4 addr) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public IntIPv4 getNetworkSource() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public WildcardMatchImpl setNetworkDestination(IntIPv4 addr) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public IntIPv4 getNetworkDestination() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public WildcardMatchImpl setNetworkProtocol(byte networkProtocol) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Byte getNetworkProtocol() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public WildcardMatchImpl setIsIPv4Fragment(boolean isFragment) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Boolean getIsIPv4Fragment() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public WildcardMatchImpl setTransportSource(short transportSource) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Short getTransportSource() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public WildcardMatchImpl setTransportDestination(short transportDestination) {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public Short getTransportDestination() {
        return null;  //To change body of implemented methods use File | Settings | File Templates.
    }
}
