/*
 * Copyright 2013 Midokura Pte Ltd.
 */
package org.midonet.api.system_data;

import java.net.URI;
import javax.xml.bind.annotation.XmlRootElement;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;

/* Class representing system state info */
@XmlRootElement
public class SystemState extends UriResource {

    private String state;
    private String availability;

    public SystemState() {
        super();
    }

    public SystemState(org.midonet.cluster.data.SystemState systemState) {
        super();
        this.state = systemState.getState();
        this.availability = systemState.getAvailability();
    }

    public org.midonet.cluster.data.SystemState toData() {
        return new org.midonet.cluster.data.SystemState()
                .setState(this.state)
                .setAvailability(this.availability);
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getAvailability() {
        return availability;
    }

    public void setAvailability(String availability) {
        this.availability = availability;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null) {
            return ResourceUriBuilder.getSystemState(getBaseUri());
        } else {
            return null;
        }
    }
}
