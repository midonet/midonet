/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midonet.api.host;

import com.midokura.midonet.api.ResourceUriBuilder;
import com.midokura.midonet.api.UriResource;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com> Date: 1/30/12
 */
@XmlRootElement
public class Host extends UriResource {

    private UUID id;
    String name;
    List<String> addresses;
    boolean alive;

    public Host() {
    }

    public Host(UUID id) {
        this.id = id;
    }

    public Host(com.midokura.midonet.cluster.data.host.Host host) {

        this.id = host.getId();
        this.name = host.getName();

        this.addresses = new ArrayList<String>();
        if (host.getAddresses() != null) {
            for (InetAddress inetAddress : host.getAddresses()) {
                this.addresses.add(inetAddress.toString());
            }
        }

        this.alive = host.getIsAlive();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getAddresses() {
        return addresses;
    }

    public void setAddresses(List<String> addresses) {
        this.addresses = addresses;
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    @Override
    public URI getUri() {
        if (super.getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getHost(super.getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the interfaces URI
     */
    public URI getInterfaces() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getHostInterfaces(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the commands URI
     */
    public URI getHostCommands() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getHostCommands(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the interface port map URI
     */
    public URI getPorts() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getHostInterfacePorts(getBaseUri(), id);
        } else {
            return null;
        }
    }
}
