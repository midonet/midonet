/*
 * @(#)Vif      1.6 11/09/24
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.state.VifZkManager.VifConfig;

/**
 * Class representing Vif.
 * 
 * @version 1.6 24 Sept 2011
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class Vif {

    private UUID id;
    private String name;
    private UUID portId;
    
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
    /**
     * @return the portId
     */
    public UUID getPortId() {
        return portId;
    }
    /**
     * @param portId the portId to set
     */
    public void setPortId(UUID portId) {
        this.portId = portId;
    }
    
    public VifConfig toConfig() {
        VifConfig c = new VifConfig();
        c.name = this.name;
        c.portId = this.portId;
        return c;
    }
    
    public static Vif createVif(VifConfig c) {
        Vif v = new Vif();
        v.setName(c.name);
        v.setPortId(c.portId);
        return v;
    }
}
