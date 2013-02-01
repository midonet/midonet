/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;

@XmlRootElement
public class DtoDhcpHost {
    protected String macAddr;
    protected String ipAddr;    // DHCP "your ip address"
    protected String name;      // DHCP option 12 - host name
    private URI uri;

    public String getMacAddr() {
        return macAddr;
    }

    public void setMacAddr(String macAddr) {
        this.macAddr = macAddr;
    }

    public String getIpAddr() {
        return ipAddr;
    }

    public void setIpAddr(String ipAddr) {
        this.ipAddr = ipAddr;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoDhcpHost that = (DtoDhcpHost) o;

        if (ipAddr != null ? !ipAddr.equals(that.ipAddr) : that.ipAddr != null)
            return false;
        if (macAddr != null
                ? !macAddr.equals(that.macAddr) : that.macAddr != null)
            return false;
        if (name != null ? !name.equals(that.name) : that.name != null)
            return false;
        if (uri != null ? !uri.equals(that.uri) : that.uri != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = macAddr != null ? macAddr.hashCode() : 0;
        result = 31 * result + (ipAddr != null ? ipAddr.hashCode() : 0);
        result = 31 * result + (name != null ? name.hashCode() : 0);
        result = 31 * result + (uri != null ? uri.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "DtoDhcpHost{" +
                "ipAddr='" + ipAddr + '\'' +
                ", macAddr='" + macAddr + '\'' +
                ", name='" + name + '\'' +
                ", uri=" + uri +
                '}';
    }
}
