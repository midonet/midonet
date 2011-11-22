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
import com.midokura.midolman.mgmt.rest_api.core.UriManager;

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
        return UriManager.getVif(getBaseUri(), id);
    }

    public VifConfig toConfig() {
        VifConfig c = new VifConfig();
        c.portId = this.portId;
        return c;
    }

    public static Vif createVif(UUID id, VifConfig c) {
        Vif v = new Vif();
        v.setId(id);
        v.setPortId(c.portId);
        return v;
    }
}
