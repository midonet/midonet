/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.api.dhcp;

import org.midonet.api.RelativeUriResource;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.cluster.data.dhcp.Host;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;

@XmlRootElement
public class DhcpHost extends RelativeUriResource {
    protected String macAddr;
    protected String ipAddr; // DHCP "your ip address"
    protected String name; // DHCP option 12 - host name

    public DhcpHost(String macAddr, String ipAddr, String name) {
        this.macAddr = macAddr;
        this.ipAddr = ipAddr;
        this.name = name;
    }

    /* Default constructor - for deserialization. */
    public DhcpHost() {
    }

    public DhcpHost(Host host) {
        this.ipAddr = host.getIp().toString();
        this.macAddr = host.getMAC().toString();
        this.name = host.getName();
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getParentUri() != null && macAddr != null) {
            return ResourceUriBuilder.getDhcpHost(getParentUri(), macAddr);
        } else {
            return null;
        }
    }

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

    public Host toData() {
        return new Host()
                .setIp(IPv4Addr.fromString(this.ipAddr))
                .setMAC(MAC.fromString(this.macAddr))
                .setName(this.name);
    }

}
