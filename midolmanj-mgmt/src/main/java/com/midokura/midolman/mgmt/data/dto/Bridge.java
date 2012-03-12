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
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;

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
     * Constructor.
     */
    public Bridge() {
        this(null, null, null);
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the bridge
     * @param config
     *            BridgeMgmtConfig object.
     */
    public Bridge(UUID id, BridgeMgmtConfig config) {
        this(id, config.name, config.tenantId);
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the bridge.
     * @param name
     *            Name of the bridge.
     * @param tenantId
     *            ID of the tenant that owns the bridge.
     */
    public Bridge(UUID id, String name, String tenantId) {
        super();
        this.id = id;
        this.name = name;
        this.tenantId = tenantId;
    }

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
        return ResourceUriBuilder.getBridgePorts(getBaseUri(), id);
    }

    /**
     * @return the routers URI
     */
    public URI getRouters() {
        return ResourceUriBuilder.getBridgeRouters(getBaseUri(), id);
    }

    /**
     * @return the Filtering Database URI
     */
   public URI getFilteringDb() {
        return ResourceUriBuilder.getFilteringDb(getBaseUri(), id);
    }

    /**
     * @return the DHCP server configuration URI
     */
    public URI getDhcpSubnets() {
        return ResourceUriBuilder.getBridgeDhcps(getBaseUri(), id);
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        return ResourceUriBuilder.getBridge(getBaseUri(), id);
    }

    /**
     * Convert this object to BridgeConfig object
     *
     * @return BridgeConfig object
     */
    public BridgeConfig toConfig() {
        return new BridgeConfig();
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
     * Convert this object to BridgeNameMgmtConfig object.
     *
     * @return BridgeNameMgmtConfig object.
     */
    public BridgeNameMgmtConfig toNameMgmtConfig() {
        return new BridgeNameMgmtConfig(this.getId());
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "id=" + id + ", name=" + name + ", tenantId=" + tenantId;
    }
}
