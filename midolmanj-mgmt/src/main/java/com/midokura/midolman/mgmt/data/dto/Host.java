/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 1/30/12
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
        return ResourceUriBuilder.getHost(super.getBaseUri(), id);
    }

    /**
     * @return the ports URI
     */
    public URI getInterfaces() {
        return ResourceUriBuilder.getHostInterfaces(getBaseUri(), id);
    }
}
