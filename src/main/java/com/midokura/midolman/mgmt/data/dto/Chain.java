/*
 * @(#)Route      1.6 11/09/10
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

/**
 * Class representing chain.
 * 
 * @version        1.6 11 Sept 2011
 * @author         Ryu Ishimoto
 */
@XmlRootElement
public class Chain {
    
    private UUID id = null;
    private UUID routerId = null;
    private String name = null;
    
    /**
     * @return the id
     */
    public UUID getId() {
        return id;
    }
    /**
     * @param id the id to set
     */
    public void setId(UUID id) {
        this.id = id;
    }
    /**
     * @return the routerId
     */
    public UUID getRouterId() {
        return routerId;
    }
    /**
     * @param routerId the routerId to set
     */
    public void setRouterId(UUID routerId) {
        this.routerId = routerId;
    }
    /**
     * @return the name
     */
    public String getName() {
        return name;
    }
    /**
     * @param name the name to set
     */
    public void setName(String name) {
        this.name = name;
    }
    
    

}
