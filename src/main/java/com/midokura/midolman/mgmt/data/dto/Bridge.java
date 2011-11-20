/*
 * @(#)Bridge        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.config.BridgeMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.BridgeNameMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.UriManager;

/**
 * Class representing Virtual Bridge.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
@XmlRootElement
public class Bridge extends UriResource {

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

    /**
     * @return the ports URI
     */
    public URI getPorts() {
        return UriManager.getBridgePorts(getBaseUri(), id);
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return UriManager.getBridge(getBaseUri(), id);
    }

    /**
     * Convert this object to BridgeMgmtConfig object.
     * 
     * @return BridgeMgmtConfig object.
     */
    public BridgeMgmtConfig toMgmtConfig() {
        return new BridgeMgmtConfig(this.getTenantId(), this.getName());
    }

    /**
     * Convert BridgeMgmtConfig object to Bridge object.
     * 
     * @param id
     *            ID of the object.
     * @param config
     *            BridgeMgmtConfig object.
     * @return Bridge object.
     */
    public static Bridge createBridge(UUID id, BridgeMgmtConfig config) {
        Bridge b = new Bridge();
        b.setName(config.name);
        b.setTenantId(config.tenantId);
        b.setId(id);
        return b;
    }

    /**
     * Convert this object to BridgeNameMgmtConfig object.
     * 
     * @return BridgeNameMgmtConfig object.
     */
    public BridgeNameMgmtConfig toNameMgmtConfig() {
        return new BridgeNameMgmtConfig(this.getId());
    }
}
