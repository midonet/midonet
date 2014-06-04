/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.client.dto;

import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;

import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import java.net.URI;
import java.util.UUID;

@XmlRootElement
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DtoGreTunnelZone.class,
                name = TunnelZoneType.GRE)})
public abstract class DtoTunnelZone {

    private UUID id;
    private String name;

    /**
     * @return The tunnel zone type
     */
    public abstract String getType();

    @XmlTransient
    private URI uri;

    @XmlTransient
    private URI hosts;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public URI getHosts() {
        return hosts;
    }

    public void setHosts(URI hosts) {
        this.hosts = hosts;
    }
}
