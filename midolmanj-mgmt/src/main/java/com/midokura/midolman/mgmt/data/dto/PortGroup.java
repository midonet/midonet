/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.config.PortGroupMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.PortGroupNameMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;

/**
 * Class representing a port group.
 */
@XmlRootElement
public class PortGroup extends UriResource {

    private UUID id = null;
    private String tenantId = null;
    private String name = null;

    /**
     * Default constructor
     */
    public PortGroup() {
        super();
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the chain
     * @param config
     *            ChainMgmtConfig object
     */
    public PortGroup(UUID id, PortGroupMgmtConfig config) {
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
    public PortGroup(UUID id, String tenantId, String name) {
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
        return ResourceUriBuilder.getChain(getBaseUri(), id);
    }

    public PortGroupMgmtConfig toMgmtConfig() {
        return new PortGroupMgmtConfig(tenantId, name);
    }

    public PortGroupNameMgmtConfig toNameMgmtConfig() {
        return new PortGroupNameMgmtConfig(this.getId());
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
