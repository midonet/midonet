/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.api.host;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.util.version.Since;
import org.midonet.util.version.Until;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com> Date: 1/30/12
 */
@XmlRootElement
public class Host extends UriResource {

    private UUID id;
    String name;
    List<String> addresses;
    boolean alive;

    private List<Interface> hostInterfaces = new ArrayList<>();

    /*
     * From specs: This weight is a non-negative integer whose default
     * value is 1. The MN administrator may set this value to zero to signify
     * that the host should never be chosen as a flooding proxy.
     *
     * Note: though null is not a valid value, we accept it to support clients
     * not providing any value (this will be converted to the proper default
     * value when stored and retrieved afterwards).
     */
    @Min(0)
    @Max(65535)
    private Integer floodingProxyWeight;

    public Host() {
    }

    public Host(UUID id) {
        this.id = id;
    }

    public Host(org.midonet.cluster.data.host.Host host) {

        this.id = host.getId();
        this.name = host.getName();
        this.floodingProxyWeight = host.getFloodingProxyWeight();

        this.addresses = new ArrayList<String>();
        if (host.getAddresses() != null) {
            for (InetAddress inetAddress : host.getAddresses()) {
                this.addresses.add(inetAddress.toString());
            }
        }

        this.alive = host.getIsAlive();
        for (org.midonet.cluster.data.host.Interface intf :
            host.getInterfaces()) {
            this.hostInterfaces.add(new Interface(this.id, intf));
        }
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

    public Integer getFloodingProxyWeight() {
        return this.floodingProxyWeight;
    }

    public void setFloodingProxyWeight(Integer floodingProxyWeight) {
        this.floodingProxyWeight = floodingProxyWeight;
    }

    @Since("3")
    public List<Interface> getHostInterfaces() {
        return this.hostInterfaces;
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
    @Until("3")
    public URI getInterfaces() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getHostInterfaces(getBaseUri(), id);
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

    @Override
    public void setBaseUri(URI baseUri) {
        super.setBaseUri(baseUri);
        for (Interface intf : this.hostInterfaces) {
            intf.setBaseUri(baseUri);
        }
    }
}
