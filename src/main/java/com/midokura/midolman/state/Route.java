package com.midokura.midolman.state;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

public class Route implements Serializable {

    public enum NextHop {
        BLACKHOLE, REJECT, PORT;
    }

    public InetAddress srcNetworkAddr;
    public int srcNetworkLength;
    public InetAddress dstNetworkAddr;
    public int dstNetworkLength;
    public NextHop nextHop;
    public UUID nextHopPort;
    public InetAddress nextHopGateway;
    public int weight;
    public String attributes;

    public Route(InetAddress srcNetworkAddr, int srcNetworkLength,
            InetAddress dstNetworkAddr, int dstNetworkLength, NextHop nextHop,
            UUID nextHopPort, InetAddress nextHopGateway, int weight,
            String attributes) {
        super();
        this.srcNetworkAddr = srcNetworkAddr;
        this.srcNetworkLength = srcNetworkLength;
        this.dstNetworkAddr = dstNetworkAddr;
        this.dstNetworkLength = dstNetworkLength;
        this.nextHop = nextHop;
        this.nextHopPort = nextHopPort;
        this.nextHopGateway = nextHopGateway;
        this.weight = weight;
        this.attributes = attributes;
    }

    @Override
    public boolean equals(Object other) {
        if (other == null)
            return false;
        if (other == this)
            return true;
        if (!(other instanceof Route))
            return false;
        Route rt = (Route) other;
        if (null == nextHopGateway || null == rt.nextHopGateway) {
            if (nextHopGateway != rt.nextHopGateway)
                return false;
        } else if (!nextHopGateway.equals(rt.nextHopGateway))
            return false;
        if (null == nextHopPort || null == rt.nextHopPort) {
            if (nextHopPort != rt.nextHopPort)
                return false;
        } else if (!nextHopPort.equals(rt.nextHopPort))
            return false;
        return attributes.equals(rt.attributes)
                && dstNetworkAddr.equals(rt.dstNetworkAddr)
                && dstNetworkLength == rt.dstNetworkLength
                && nextHop.equals(rt.nextHop)
                && srcNetworkAddr.equals(rt.srcNetworkAddr)
                && srcNetworkLength == rt.srcNetworkLength
                && weight == rt.weight;
    }

    @Override
    public int hashCode() {
        int hash = 1;
        hash = 13 * hash + srcNetworkAddr.hashCode();
        hash = 17 * hash + srcNetworkLength;
        hash = 31 * hash + dstNetworkAddr.hashCode();
        hash = 23 * hash + dstNetworkLength;
        hash = 29 * hash + nextHop.hashCode();
        if (null != nextHopPort)
            hash = 43 * hash + nextHopPort.hashCode();
        if (null != nextHopPort)
            hash = 37 * hash + nextHopPort.hashCode();
        hash = 11 * hash + weight;
        hash = 5 * hash + attributes.hashCode();
        return hash;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(srcNetworkAddr.getHostAddress()).append(",");
        sb.append(srcNetworkLength).append(",");
        sb.append(dstNetworkAddr.getHostAddress()).append(",");
        sb.append(dstNetworkLength).append(",");
        sb.append(nextHop.toString()).append(",");
        if (null != nextHopPort)
            sb.append(nextHopPort.toString());
        sb.append(",");
        if (null != nextHopGateway)
            sb.append(nextHopGateway.getHostAddress());
        sb.append(",");
        sb.append(weight).append(",");
        sb.append(attributes);
        return sb.toString();
    }

    public static Route fromString(String str) throws NumberFormatException,
            UnknownHostException {
        String[] parts = str.split(",");
        Route rt = new Route(InetAddress.getByName(parts[0]),
                Byte.parseByte(parts[1]), InetAddress.getByName(parts[2]),
                Byte.parseByte(parts[3]), NextHop.valueOf(parts[4]),
                parts[5].isEmpty() ? null : UUID.fromString(parts[5]),
                parts[6].isEmpty() ? null : InetAddress.getByName(parts[6]),
                Integer.parseInt(parts[7]), parts[8]);
        return rt;
    }

}
