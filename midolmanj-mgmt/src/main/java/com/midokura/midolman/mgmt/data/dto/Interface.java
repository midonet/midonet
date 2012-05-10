/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.InetAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;

/**
 * @author Mihai ClaudiuToader <mtoader@midokura.com>
 *         Date: 1/30/12
 */
@XmlRootElement
public class Interface extends UriResource {

    UUID id;
    UUID hostId;
    String name;
    String mac;
    int mtu;
    int status;
    Type type;
    String endpoint;
    InetAddress[] addresses;
    Map<String, String> properties = new HashMap<String, String>();

    public enum Type {
        Physical, Virtual, Tunnel, Unknown
    }

    public Interface() {
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

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    @Override
    public URI getUri() {
        return ResourceUriBuilder.getHostInterface(super.getBaseUri(), hostId,
                                                   id);
    }

}
