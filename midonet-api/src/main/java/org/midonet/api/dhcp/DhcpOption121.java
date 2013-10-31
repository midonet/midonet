/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.api.dhcp;

import org.midonet.cluster.data.dhcp.Opt121;
import org.midonet.packets.IntIPv4;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DhcpOption121 {

    private String destinationPrefix;
    private int destinationLength;
    private String gatewayAddr;

    /* Default constructor for parsing. */
    public DhcpOption121() {
    }

    public DhcpOption121(String destinationPrefix, int destinationLength,
                         String gatewayAddr) {
        this.destinationPrefix = destinationPrefix;
        this.destinationLength = destinationLength;
        this.gatewayAddr = gatewayAddr;
    }

    public DhcpOption121(Opt121 opt121) {
        this(opt121.getRtDstSubnet().toUnicastString(),
                opt121.getRtDstSubnet().getMaskLength(),
                opt121.getGateway().toString());
    }

    public String getDestinationPrefix() {
        return destinationPrefix;
    }

    public void setDestinationPrefix(String destinationPrefix) {
        this.destinationPrefix = destinationPrefix;
    }

    public int getDestinationLength() {
        return destinationLength;
    }

    public void setDestinationLength(int destinationLength) {
        this.destinationLength = destinationLength;
    }

    public String getGatewayAddr() {
        return gatewayAddr;
    }

    public void setGatewayAddr(String gatewayAddr) {
        this.gatewayAddr = gatewayAddr;
    }

    public Opt121 toData() {
        return new Opt121()
            .setGateway(
                IntIPv4.toIPv4Subnet(IntIPv4.fromString(gatewayAddr)))
            .setRtDstSubnet(
                IntIPv4.toIPv4Subnet(
                    IntIPv4.fromString(destinationPrefix, destinationLength)));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DhcpOption121 that = (DhcpOption121) o;

        if (destinationLength != that.destinationLength) return false;
        if (destinationPrefix != null
                ? !destinationPrefix.equals(that.destinationPrefix)
                : that.destinationPrefix != null)
            return false;
        if (gatewayAddr != null
                ? !gatewayAddr.equals(that.gatewayAddr)
                : that.gatewayAddr != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = destinationPrefix != null
                ? destinationPrefix.hashCode() : 0;
        result = 31 * result + destinationLength;
        result = 31 * result + (gatewayAddr != null
                ? gatewayAddr.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "DtoDhcpOption121{" +
                "destinationLength=" + destinationLength +
                ", destinationPrefix='" + destinationPrefix + '\'' +
                ", gatewayAddr='" + gatewayAddr + '\'' +
                '}';
    }
}
