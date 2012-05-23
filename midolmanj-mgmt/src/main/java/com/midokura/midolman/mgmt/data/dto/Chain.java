/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.config.ChainMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.ChainNameMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;

/**
 * Class representing chain.
 */
@XmlRootElement
public class Chain extends UriResource {

    private UUID id = null;
    private String tenantId = null;
    private String name = null;

    /**
     * Default constructor
     */
    public Chain() {
        super();
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the chain
     * @param config
     *            ChainConfig object
     * @param config
     *            ChainMgmtConfig object
     */
    public Chain(UUID id, ChainMgmtConfig config) {
        this(id, config.tenantId, config.name);
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the chain
     * @param tenantId
     *            Tenant ID
     * @param name
     *            Chain name
     */
    public Chain(UUID id, String tenantId, String name) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
    }

    /**
     * @return the id
     */
    public UUID getId() {
        return id;
    }

    /**
     * @param id
     *            the id to set
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * @return the tenantId
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * @param tenantId
     *            the tenantId to set
     */
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * @param name
     *            the name to set
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getChain(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the rules URI
     */
    public URI getRules() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getChainRules(getBaseUri(), id);
        } else {
            return null;
        }
    }

    public ChainConfig toConfig() {
        return new ChainConfig(this.getName());
    }

    public ChainMgmtConfig toMgmtConfig() {
        return new ChainMgmtConfig(tenantId, name);
    }

    public ChainNameMgmtConfig toNameMgmtConfig() {
        return new ChainNameMgmtConfig(this.getId());
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "id=" + id + " tenantId=" + tenantId + ", name=" + name;
    }

}
