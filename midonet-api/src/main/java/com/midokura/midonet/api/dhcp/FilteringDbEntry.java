/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.api.dhcp;

import com.midokura.midonet.api.UriResource;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class FilteringDbEntry extends UriResource {
    String macAddr;
    UUID portId;

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return null;
        //return ResourceUriBuilder.getFilterDbEntry(getBaseUri(), macAddr);
    }

    public String getMacAddr() {
        return macAddr;
    }

    public void setMacAddr(String macAddr) {
        this.macAddr = macAddr;
    }

    public UUID getPortId() {
        return portId;
    }

    public void setPortId(UUID portId) {
        this.portId = portId;
    }
}
