/*
 * @(#)Router        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

/**
 * Class representing Virtual Router.
 *
 * @version        1.6 05 Sept 2011
 * @author         Ryu Ishimoto
 */
@XmlRootElement
public class Router {

    private UUID id = null;
    private String name = null;
    private UUID tenantId = null;

    /**
     * Get router ID.
     * 
     * @return  Router ID.
     */
    public UUID getId() {
        return id;
    }

    /**
     * Set router ID.
     * 
     * @param  id  ID of the router.
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * Get router name.
     * 
     * @return  Router name.
     */
    public String getName() {
        return name;
    }

    /**
     * Set router name.
     * 
     * @param  name  Name of the router.
     */
    public void setName(String name) {
        this.name = name;
    }
    
    /**
     * Get tenant ID.
     * 
     * @return  Tenant ID.
     */
    public UUID getTenantId() {
        return tenantId;
    }

    /**
     * Set tenant ID.
     * 
     * @param  tenantId  Tenant ID of the router.
     */
    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }    
}
