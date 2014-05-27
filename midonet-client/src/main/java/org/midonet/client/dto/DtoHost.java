/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import java.net.URI;
import java.util.UUID;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 1/31/12
 */
@XmlRootElement
public class DtoHost {

    private UUID id;
    private String name;
    private String[] addresses;
    private URI interfaces;
    private URI hostCommands;
    private URI ports;
    private boolean alive;
    private Integer floodingProxyWeight;

    @XmlTransient
    private URI uri;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public URI getInterfaces() {
        return interfaces;
    }

    public void setInterfaces(URI interfaces) {
        this.interfaces = interfaces;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String[] getAddresses() {
        return addresses;
    }

    public void setAddresses(String[] addresses) {
        this.addresses = addresses;
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public Integer getFloodingProxyWeight() {
        return floodingProxyWeight;
    }

    public void setFloodingProxyWeight(Integer floodingProxyWeight) {
        this.floodingProxyWeight = floodingProxyWeight;
    }

    public URI getHostCommands() {
        return hostCommands;
    }

    public void setHostCommands(URI hostCommands) {
        this.hostCommands = hostCommands;
    }

    public URI getPorts() {
        return ports;
    }

    public void setPorts(URI ports) {
        this.ports = ports;
    }
}
