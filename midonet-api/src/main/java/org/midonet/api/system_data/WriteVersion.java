/*
 * Copyright 2013 Midokura Pte Ltd.
 */
package org.midonet.api.system_data;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;

/* Class representing the Write Version of the zookeeper data */
@XmlRootElement
public class WriteVersion extends UriResource {

    private String version;

    public WriteVersion() {
        super();
    }

    public WriteVersion(org.midonet.cluster.data.WriteVersion writeVersion) {
        super();
        this.version = writeVersion.getVersion();
    }

    public org.midonet.cluster.data.WriteVersion toData() {
        return new org.midonet.cluster.data.WriteVersion()
                .setVersion(this.version);
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getWriteVersion(getBaseUri());
        } else {
            return null;
        }
    }
}
