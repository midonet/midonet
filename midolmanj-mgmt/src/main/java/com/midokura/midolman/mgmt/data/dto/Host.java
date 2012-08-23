/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;

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
    public URI getInterfacePortMap() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getHostInterfacePortMap(getBaseUri(),
                    id);
        } else {
            return null;
        }
    }
}
