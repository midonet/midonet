/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.layer3;

import com.google.common.base.Objects;
import java.io.Serializable;
import java.util.UUID;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonPropertyOrder;

import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;


@JsonPropertyOrder(alphabetic=true)
public class Route implements Serializable {

    private static final long serialVersionUID = -5913569441176193396L;
    public static final int NO_GATEWAY = 0xffffffff;
    public static final int DEFAULT_WEIGHT = 100;

    public enum NextHop {
        BLACKHOLE, REJECT, PORT, LOCAL;

        public boolean toPort() {
            return this.equals(PORT) || this.equals(LOCAL);
        }
    }

    public int srcNetworkAddr;
    public int srcNetworkLength;
    public int dstNetworkAddr;
    public int dstNetworkLength;
    public NextHop nextHop;
    public UUID nextHopPort;
    public int nextHopGateway;
    public int weight;
    public String attributes;
    public UUID routerId;

    public Route(int srcNetworkAddr, int srcNetworkLength, int dstNetworkAddr,
            int dstNetworkLength, NextHop nextHop, UUID nextHopPort,
            int nextHopGateway, int weight, String attributes, UUID routerId) {
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
        this.routerId = routerId;
    }

    public Route(IPv4Subnet srcSubnet, IPv4Subnet dstSubnet,
                 NextHop nextHop, UUID nextHopPortId, IPv4Addr nextHopGw,
                 int weight, UUID routerId) {
        this(srcSubnet.getIntAddress(), srcSubnet.getPrefixLen(),
                dstSubnet.getIntAddress(), dstSubnet.getPrefixLen(),
                nextHop, nextHopPortId,
                nextHopGw == null ? 0 : nextHopGw.addr(),
                weight, null, routerId);
    }

    // Default constructor for the Jackson deserialization.
    public Route() {
        super();
    }

    /* Custom accessors for more readable IP address representation in Jackson serialization. */

    public String getSrcNetworkAddr() {
        return IPv4Addr.intToString(this.srcNetworkAddr);
    }

    public void setSrcNetworkAddr(String addr) {
        this.srcNetworkAddr = IPv4Addr.stringToInt(addr);
    }

    public String getDstNetworkAddr() {
        return IPv4Addr.intToString(this.dstNetworkAddr);
    }

    public void setDstNetworkAddr(String addr) {
        this.dstNetworkAddr = IPv4Addr.stringToInt(addr);
    }

    public String getNextHopGateway() {
        return IPv4Addr.intToString(this.nextHopGateway);
    }

    public void setNextHopGateway(String addr) {
        this.nextHopGateway = IPv4Addr.stringToInt(addr);
    }

    public boolean isEquivalentRoute(Object other) {
        if (other == null)
            return false;
        if (other == this)
            return true;
        if (!(other instanceof Route))
            return false;
        Route rt = (Route) other;
        return (Objects.equal(this.srcNetworkAddr, rt.srcNetworkAddr) &&
                Objects.equal(this.srcNetworkLength, rt.srcNetworkLength) &&
                Objects.equal(this.dstNetworkAddr, rt.dstNetworkAddr) &&
                Objects.equal(this.dstNetworkLength, rt.dstNetworkLength) &&
                Objects.equal(this.nextHopPort, rt.nextHopPort) &&
                Objects.equal(this.routerId, rt.routerId));
    }

    @Override
    public final boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null) return false;
        if (!(obj instanceof Route)) return false;
        final Route rt = (Route) obj;
        return (Objects.equal(this.srcNetworkAddr, rt.srcNetworkAddr)
                && Objects.equal(this.srcNetworkLength, rt.srcNetworkLength)
                && Objects.equal(this.dstNetworkAddr, rt.dstNetworkAddr)
                && Objects.equal(this.dstNetworkLength, rt.dstNetworkLength)
                && Objects.equal(this.nextHop, rt.nextHop)
                && Objects.equal(this.nextHopPort, rt.nextHopPort)
                && Objects.equal(this.nextHopGateway, rt.nextHopGateway)
                && Objects.equal(this.weight, rt.weight)
                && Objects.equal(this.attributes, rt.attributes)
                && Objects.equal(this.routerId, rt.routerId));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(srcNetworkAddr, srcNetworkLength,
                                dstNetworkAddr, dstNetworkLength, nextHop,
                                nextHopPort, nextHopGateway, weight, attributes,
                                routerId);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(IPv4Addr.intToString(srcNetworkAddr)).append("/");
        sb.append(srcNetworkLength).append(",");
        sb.append(IPv4Addr.intToString(dstNetworkAddr)).append("/");
        sb.append(dstNetworkLength).append(",");
        if (null != nextHop)
            sb.append(nextHop.toString());
        sb.append(",");
        if (null != nextHopPort)
            sb.append(nextHopPort.toString());
        sb.append(",");
        sb.append(nextHopGateway).append(",");
        sb.append(weight).append(",");
        if (null != attributes)
            sb.append(attributes);
        if (null != routerId)
            sb.append(routerId);
        return sb.toString();
    }

    public static Route fromString(String str) {
        String[] parts = str.split(",");
        Route rt = new Route(Integer.parseInt(parts[0]),
                Byte.parseByte(parts[1]), Integer.parseInt(parts[2]),
                Byte.parseByte(parts[3]), parts[4].isEmpty() ? null
                        : NextHop.valueOf(parts[4]), parts[5].isEmpty() ? null
                        : UUID.fromString(parts[5]),
                Integer.parseInt(parts[6]), Integer.parseInt(parts[7]),
                parts.length > 8? parts[8] : null, null);
        return rt;
    }

    public static Route defaultRoute(UUID nextHopPortId, int weight,
                                     UUID routerId) {
        return new Route(0, 0, 0, 0, NextHop.PORT, nextHopPortId,
                Route.NO_GATEWAY, weight, null, routerId);
    }

    public static Route nextHopPortRoute(IPv4Subnet srcSubnet,
                                         IPv4Subnet dstSubnet,
                                         UUID nextHopPortId, IPv4Addr nextHopGw,
                                         int weight, UUID routerId) {
        return new Route(srcSubnet, dstSubnet, NextHop.PORT,
                nextHopPortId, nextHopGw, weight, routerId);
    }

    public static Route localRoute(UUID portId, int portAddr, UUID routerId) {
        return new Route(0, 0, portAddr, 32, Route.NextHop.LOCAL,
                portId, Route.NO_GATEWAY, 0, null, routerId);
    }

    @JsonIgnore
    public boolean hasDstSubnet(IPv4Subnet sub) {
        if (sub == null) {
            throw new IllegalArgumentException("sub must not be NULL");
        }
        return dstNetworkAddr == sub.getIntAddress() &&
                dstNetworkLength == sub.getPrefixLen();
    }

    @JsonIgnore
    public boolean hasNextHopGateway(int gateway) {
        return nextHopGateway == gateway;
    }
}
