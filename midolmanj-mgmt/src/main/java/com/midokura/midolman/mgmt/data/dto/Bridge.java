/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import javax.validation.GroupSequence;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.Bridge.BridgeExtended;
import com.midokura.midolman.mgmt.data.dto.config.BridgeNameMgmtConfig;
import com.midokura.midolman.mgmt.jaxrs.validation.annotation.IsUniqueBridgeName;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.state.zkManagers.BridgeZkManager.BridgeConfig;

/**
 * Class representing Virtual Bridge.
 */
@IsUniqueBridgeName(groups = BridgeExtended.class)
@XmlRootElement
public class Bridge extends UriResource {

    public static final int MIN_BRIDGE_NAME_LEN = 1;
    public static final int MAX_BRIDGE_NAME_LEN = 255;

    private UUID id;
    private UUID inboundFilterId;
    private UUID outboundFilterId;
    private String tenantId;

    @NotNull
    @Size(min = MIN_BRIDGE_NAME_LEN, max = MAX_BRIDGE_NAME_LEN)
    private String name;

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
     * Bridge constructor
     *
     * @param id
     *            Bridge ID
     * @param config
     *            BridgeConfig object
     */
    public Bridge(UUID id, BridgeConfig config) {
        this(id, config.name, config.properties.get(ConfigProperty.TENANT_ID));
        this.inboundFilterId = config.inboundFilter;
        this.outboundFilterId = config.outboundFilter;
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
        BridgeConfig config = new BridgeConfig(this.name, this.inboundFilterId,
                this.outboundFilterId);
        config.properties.put(ConfigProperty.TENANT_ID, this.tenantId);
        return config;
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

    /**
     * Interface used for a Validation group. This group gets triggered after
     * the default validations.
     */
    public interface BridgeExtended {
    }

    /**
     * Interface that defines the ordering of validation groups.
     */
    @GroupSequence({ Default.class, BridgeExtended.class })
    public interface BridgeGroupSequence {
    }
}
