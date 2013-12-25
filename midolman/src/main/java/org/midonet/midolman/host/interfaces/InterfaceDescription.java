/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.host.interfaces;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.midonet.packets.MAC;
import org.midonet.odp.DpPort;
import org.midonet.odp.PortOptions;


/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/8/12
 */
public class InterfaceDescription {

    public enum Type { PHYS, VIRT, TUNN, UNKNOWN }
    public enum Endpoint { DATAPATH, PHYSICAL, VM, GRE, CAPWAP, LOCALHOST,
        TUNTAP, UNKNOWN }

    ///////////////////////////////////////////////////////////////////////////
    // Attributes
    ///////////////////////////////////////////////////////////////////////////
    protected String name;
    protected Type type;
    protected MAC mac;
    protected List<InetAddress> inetAddresses;
    protected boolean isUp;
    protected boolean hasLink;
    protected int mtu;
    protected Endpoint endpoint;
    protected DpPort.Type portType;
    //protected ... other
    protected Map<String, String> properties;

    ///////////////////////////////////////////////////////////////////////////
    // Public methods
    ///////////////////////////////////////////////////////////////////////////
    public InterfaceDescription(String name) {
        this.name = name;
        this.type = Type.UNKNOWN;
        this.mac = MAC.fromString("00:00:00:00:00:00");
        this.inetAddresses = new LinkedList<InetAddress>();
        this.isUp = false;
        this.hasLink = false;
        this.mtu = 0;
        this.endpoint = Endpoint.UNKNOWN;
        properties = new HashMap<String, String>();
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return this.name;
    }

    public void setMac(String macString) {
        this.mac = MAC.fromString(macString);
    }

    public byte[] getMac() {
        return mac.getAddress();
    }

    public void setType(Type type) {
        this.type = type;
    }

    public Type getType() {
        return type;
    }

    public void setInetAddress(InetAddress inetAddress) {
        this.inetAddresses.add(inetAddress);
    }

    public void setInetAddress(String addressString) {
        try {
            InetAddress inetAddress = InetAddress.getByName(addressString);
            this.inetAddresses.add(inetAddress);
        } catch (Exception ignored) {
            // We allow the interfaceDescription not to have any IP addresses.
            // If the IP address conversion fails, we just don't add it to
            // the list.
        }
    }

    public List<InetAddress> getInetAddresses() {
        return inetAddresses;
    }

    public void setUp(boolean up) {
        isUp = up;
    }

    public boolean isUp() {
        return isUp;
    }

    public void setHasLink(boolean hasLink) {
        this.hasLink = hasLink;
    }

    public boolean hasLink() {
        return hasLink;
    }

    public void setMtu(int mtu) {
        this.mtu = mtu;
    }

    public int getMtu() {
        return mtu;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    public DpPort.Type getPortType() {
        return portType;
    }

    public void setPortType(DpPort.Type portType) {
        this.portType = portType;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    @Override
    public String toString() {
        return "InterfaceDescription{" +
            "name='" + name + '\'' +
            ", type=" + type +
            ", mac=" + mac +
            ", inetAddresses=" + inetAddresses +
            ", isUp=" + isUp +
            ", hasLink=" + hasLink +
            ", mtu=" + mtu +
            ", endpoint=" + endpoint +
            ", portType=" + portType +
            ", properties=" + properties +
            '}';
    }
}
