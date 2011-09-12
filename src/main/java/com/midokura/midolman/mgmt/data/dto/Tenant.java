/*
 * @(#)Tenant        1.6 11/09/07
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class Tenant {

    private UUID id = null;

    /**
     * Get tenant ID.
     * 
     * @return Tenant ID.
     */
    public UUID getId() {
        return id;
    }

    /**
     * Set tenant ID.
     * 
     * @param id
     *            ID of the tenant.
     */
    public void setId(UUID id) {
        this.id = id;
    }

}
