/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.midolman.state.zkManagers.BridgeDhcpZkManager.Host;

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

    public Host toStateHost() {
        return new Host(MAC.fromString(macAddr), IntIPv4.fromString(ipAddr),
                name);
    }

    public static DhcpHost fromStateHost(Host h) {
        return new DhcpHost(h.getMac().toString(), h.getIp().toString(),
                h.getName());
    }
}
