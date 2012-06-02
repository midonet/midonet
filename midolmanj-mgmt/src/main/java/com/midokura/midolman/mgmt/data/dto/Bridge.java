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
    private UUID inboundFilterId;
    private UUID outboundFilterId;

    /**
     * Constructor.
     */
    public Bridge() {
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

    public UUID getInboundFilterId() {
        return inboundFilterId;
    }

    public URI getInboundFilter() {
        if (getBaseUri() != null && inboundFilterId != null) {
            return ResourceUriBuilder.getChain(getBaseUri(), inboundFilterId);
        } else {
            return null;
        }
    }

    public void setInboundFilterId(UUID inboundFilterId) {
        this.inboundFilterId = inboundFilterId;
    }

    public UUID getOutboundFilterId() {
        return outboundFilterId;
    }

    public void setOutboundFilterId(UUID outboundFilterId) {
        this.outboundFilterId = outboundFilterId;
    }

    public URI getOutboundFilter() {
        if (getBaseUri() != null && outboundFilterId != null) {
            return ResourceUriBuilder.getChain(getBaseUri(), outboundFilterId);
        } else {
            return null;
        }
    }

    /**
     * @return the ports URI
     */
    public URI getPorts() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getBridgePorts(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the peer ports URI
     */
    public URI getPeerPorts() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getBridgePeerPorts(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the Filtering Database URI
     */
    public URI getFilteringDb() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getFilteringDb(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the DHCP server configuration URI
     */
    public URI getDhcpSubnets() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getBridgeDhcps(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getBridge(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * Convert this object to BridgeConfig object
     *
     * @return BridgeConfig object
     */
    public BridgeConfig toConfig() {
        return new BridgeConfig(this.inboundFilterId, this.outboundFilterId);
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
