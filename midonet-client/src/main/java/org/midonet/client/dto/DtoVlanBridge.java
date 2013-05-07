/*
 * Copyright 2011 Midokura Japan
 */

package org.midonet.client.dto;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoVlanBridge {
    private UUID id;
    private String name;
    private String tenantId;
    private URI uri;
    private URI trunkPorts;
    private URI interiorPorts;

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

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public URI getTrunkPorts() {
        return trunkPorts;
    }

    public void setTrunkPorts(URI trunkPorts) {
        this.trunkPorts = trunkPorts;
    }

    public URI getInteriorPorts() {
        return interiorPorts;
    }

    public void setInteriorPorts(URI interiorPorts) {
        this.interiorPorts = interiorPorts;
    }

}
