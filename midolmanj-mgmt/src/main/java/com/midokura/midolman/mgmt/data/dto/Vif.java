/*
 * @(#)Vif      1.6 11/09/24
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.config.VifConfig;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;

/**
 * Class representing Vif.
 *
 * @version 1.6 24 Sept 2011
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class Vif extends UriResource {

    private UUID id;
    private UUID portId;

    /**
     * Constructor
     */
    public Vif() {
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the VIF
     * @param portId
     *            Port I
     */
    public Vif(UUID id, UUID portId) {
        this.id = id;
        this.portId = portId;
    }

    /**
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * @param id
     *            the id to set
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * @return the portId
     */
    public UUID getPortId() {
        return portId;
    }

    /**
     * @param portId
     *            the portId to set
     */
    public void setPortId(UUID portId) {
        this.portId = portId;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return ResourceUriBuilder.getVif(getBaseUri(), id);
    }

    /**
     * Convert to VifConfig object.
     *
     * @return VifConfig object.
     */
    public VifConfig toConfig() {
        VifConfig c = new VifConfig();
        c.portId = this.portId;
        return c;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "id=" + id + ", portId=" + portId;
    }

}
