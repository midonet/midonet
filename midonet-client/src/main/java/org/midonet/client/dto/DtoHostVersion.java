/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */

package org.midonet.client.dto;

import com.google.common.base.Objects;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

@XmlRootElement
public class DtoHostVersion {
    private String version;
    private URI host;
    private UUID hostId;

    public String getVersion() {
        return this.version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public URI getHost() {
        return this.host;
    }

    public void setHost(URI host) {
        this.host = host;
    }

    public UUID getHostId() {
        return this.hostId;
    }

    public void setHostId(UUID hostId) {
        this.hostId = hostId;
    }

    @Override
    public boolean equals(Object other) {

        if (other == this) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        DtoHostVersion otherHostVersion = (DtoHostVersion) other;
        if (!this.version.equals(otherHostVersion.getVersion())) {
            return false;
        }

        if (!this.host.equals(otherHostVersion.getHost())) {
            return false;
        }

        if (!this.host.equals(otherHostVersion.getHost())) {
            return false;
        }

        return true;
    }
}
