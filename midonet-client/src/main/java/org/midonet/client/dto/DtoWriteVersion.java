/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */

package org.midonet.client.dto;

import com.google.common.base.Objects;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;

@XmlRootElement
public class DtoWriteVersion {
    private String version;
    private URI uri;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public URI getUri() {
        return this.uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    @Override
    public boolean equals(Object other) {

        if (other == this) {
            return true;
        }

        if (other == null || getClass() != other.getClass()) {
            return false;
        }

        DtoWriteVersion otherWriteVersion = (DtoWriteVersion) other;
        if (!Objects.equal(this.version, otherWriteVersion.getVersion())) {
            return false;
        }

        if (!Objects.equal(this.uri, otherWriteVersion.getUri())) {
            return false;
        }

        return true;
    }
}
