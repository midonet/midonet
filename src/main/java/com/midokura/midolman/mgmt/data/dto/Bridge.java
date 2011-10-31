/*
 * @(#)Bridge        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.config.BridgeMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.BridgeNameMgmtConfig;

/**
 * Class representing Virtual Bridge.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class Bridge {

    private UUID id = null;
    private String name = null;
    private String tenantId = null;

    /**
     * Get bridge ID.
     * 
     * @return Bridge ID.
     */
    public UUID getId() {
        return id;
    }

    /**
     * Set bridge ID.
     * 
     * @param id
     *            ID of the bridge.
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * Get bridge name.
     * 
     * @return Bridge name.
     */
    public String getName() {
        return name;
    }

    /**
     * Set bridge name.
     * 
     * @param name
     *            Name of the bridge.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Get tenant ID.
     * 
     * @return Tenant ID.
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Set tenant ID.
     * 
     * @param tenantId
     *            Tenant ID of the bridge.
     */
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public BridgeMgmtConfig toMgmtConfig() {
        return new BridgeMgmtConfig(this.getTenantId(), this.getName());
    }

    public static Bridge createBridge(UUID id, BridgeMgmtConfig config) {
        Bridge b = new Bridge();
        b.setName(config.name);
        b.setTenantId(config.tenantId);
        b.setId(id);
        return b;
    }

    public BridgeNameMgmtConfig toNameMgmtConfig() {
        return new BridgeNameMgmtConfig(this.getId());
    }
}
