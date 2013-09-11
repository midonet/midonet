/*
 * Copyright 2013 Midokura Pte Ltd.
 */
package org.midonet.api.system_data;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;

/* Class representing system state info */
@XmlRootElement
public class HostVersion extends UriResource {

    private String version;
    private UUID hostId;
    private URI host;

    /**
     * this field is irrelevant to HostVersion. Putting the JsonIgnore tag
     * will cause it to be left out of the serialized output even though
     * it is part of the parent class.
     */
    @JsonIgnore
    private URI uri;

    public HostVersion() {
        super();
    }

    public HostVersion(org.midonet.cluster.data.HostVersion hostVersionData) {
        super();
        this.version = hostVersionData.getVersion();
        this.hostId = hostVersionData.getHostId();
    }

    public org.midonet.cluster.data.HostVersion toData() {
        return new org.midonet.cluster.data.HostVersion()
                .setVersion(this.version)
                .setHostId(this.hostId);
    }

    public String getVersion() {
        return this.version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public UUID getHostId() {
        return this.hostId;
    }

    public void seHostId(UUID id) {
        this.hostId = id;
    }

    public URI getHost() {
        return this.host;
    }

    public void setHost(URI host) {
        this.host = host;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return null;
    }
}
