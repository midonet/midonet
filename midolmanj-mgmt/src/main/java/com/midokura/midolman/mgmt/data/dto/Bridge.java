/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
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
 */
@XmlRootElement
public class Bridge extends UriResource {

    private UUID id;
    private String name;
    private String tenantId;
    private UUID inboundFilter;
    private UUID outboundFilter;

    /**
     * Constructor.
     */
    public Bridge() {}

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

    public UUID getInboundFilter() {
        return inboundFilter;
    }

    public void setInboundFilter(UUID inboundFilter) {
        this.inboundFilter = inboundFilter;
    }

    public UUID getOutboundFilter() {
        return outboundFilter;
    }

    public void setOutboundFilter(UUID outboundFilter) {
        this.outboundFilter = outboundFilter;
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
    public URI getPeerRouters() {
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
        return new BridgeConfig(this.inboundFilter, this.outboundFilter);

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
