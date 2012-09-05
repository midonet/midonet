/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.host;

import com.midokura.midolman.host.state.HostDirectory;
import com.midokura.midolman.mgmt.ResourceUriBuilder;
import com.midokura.midolman.mgmt.UriResource;
import com.midokura.packets.MAC;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.InetAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author Mihai ClaudiuToader <mtoader@midokura.com> Date: 1/30/12
 */
@XmlRootElement
public class Interface extends UriResource {

    UUID hostId;
    String name;
    String mac;
    int mtu;
    int status;
    Type type;
    String endpoint;
    String portType;
    InetAddress[] addresses;
    Map<String, String> properties = new HashMap<String, String>();

    public enum Type {
        Physical, Virtual, Tunnel, Unknown
    }

    public Interface() {
    }

    public Interface(UUID hostId,
                     com.midokura.midonet.cluster.data.host.Interface
                             interfaceData) {
        this.setName(interfaceData.getName());
        if (interfaceData.getMac() != null) {
            this.setMac(new MAC(interfaceData.getMac()).toString());
        }
        this.setStatus(interfaceData.getStatus());
        this.setMtu(interfaceData.getMtu());
        this.setHostId(hostId);
        if (interfaceData.getType() != null) {
            this.setType(Interface.Type.valueOf(interfaceData
                    .getType().name()));
        }
        this.setAddresses(interfaceData.getAddresses());
        this.setEndpoint(interfaceData.getEndpoint());
        this.setProperties(interfaceData.getProperties());
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMac() {
        return mac;
    }

    public void setMac(String mac) {
        this.mac = mac;
    }

    public int getMtu() {
        return mtu;
    }

    public void setMtu(int mtu) {
        this.mtu = mtu;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public UUID getHostId() {
        return hostId;
    }

    public void setHostId(UUID hostId) {
        this.hostId = hostId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public InetAddress[] getAddresses() {
        return addresses;
    }

    public void setAddresses(InetAddress[] addresses) {
        this.addresses = addresses;
    }

    public String getPortType() {
        return portType;
    }

    public void setPortType(String portType) {
        this.portType = portType;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    public com.midokura.midonet.cluster.data.host.Interface toData() {

        byte[] mac = null;
        if (this.getMac() != null) {
            mac = MAC.fromString(this.getMac()).getAddress();
        }

        HostDirectory.Interface.Type type = null;
        if (this.getType() != null) {
            type = HostDirectory.Interface.Type.valueOf(this
                    .getType().name());
        }

        return new com.midokura.midonet.cluster.data.host.Interface()
                .setName(this.name)
                .setMac(mac)
                .setStatus(this.getStatus())
                .setMtu(this.getMtu())
                .setType(type)
                .setAddresses(this.getAddresses())
                .setEndpoint(this.getEndpoint())
                .setProperties(this.getProperties());
    }

    @Override
    public URI getUri() {
        if (super.getBaseUri() != null && hostId != null && name != null) {
            return ResourceUriBuilder.getHostInterface(super.getBaseUri(),
                    hostId, name);
        } else {
            return null;
        }
    }

}
