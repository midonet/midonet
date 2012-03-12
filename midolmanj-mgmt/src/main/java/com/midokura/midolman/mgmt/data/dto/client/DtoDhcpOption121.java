/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto.client;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoDhcpOption121 {
    protected String destinationPrefix;
    protected int destinationLength;
    protected String gatewayAddr;

    /*  Default constructor for parsing. */
    public DtoDhcpOption121() {
    }

    public DtoDhcpOption121(String destinationPrefix, int destinationLength,
                            String gatewayAddr) {
        this.destinationPrefix = destinationPrefix;
        this.destinationLength = destinationLength;
        this.gatewayAddr = gatewayAddr;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoDhcpOption121 that = (DtoDhcpOption121) o;

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
