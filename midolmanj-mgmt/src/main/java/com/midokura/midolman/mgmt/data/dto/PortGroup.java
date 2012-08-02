/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.data.dto;

import java.net.URI;
import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midolman.mgmt.data.dto.config.PortGroupNameMgmtConfig;
import com.midokura.midolman.mgmt.rest_api.core.ResourceUriBuilder;
import com.midokura.midolman.state.zkManagers.PortGroupZkManager.PortGroupConfig;

/**
 * Class representing a port group.
 */
@XmlRootElement
public class PortGroup extends UriResource {

    public static final int MIN_PORT_GROUP_NAME_LEN = 1;
    public static final int MAX_PORT_GROUP_NAME_LEN = 255;

    private UUID id = null;
    private String tenantId = null;

    @NotNull
    @Size(min = MIN_PORT_GROUP_NAME_LEN, max = MAX_PORT_GROUP_NAME_LEN)
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
     *            ID of the PortGroup
     * @param config
     *            PortGroupConfig object
     */
    public PortGroup(UUID id, PortGroupConfig config) {
        this(id, config.name, config.properties.get(ConfigProperty.TENANT_ID));
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the PortGroup
     * @param name
     *            PortGroup name
     * @param tenantId
     *            Tenant ID
     */
    public PortGroup(UUID id, String name, String tenantId) {
        this.id = id;
        this.name = name;
        this.tenantId = tenantId;
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
            return ResourceUriBuilder.getPortGroup(getBaseUri(), id);
        } else {
            return null;
        }
    }

    public PortGroupConfig toConfig() {
        PortGroupConfig config = new PortGroupConfig(name);
        config.properties.put(ConfigProperty.TENANT_ID, this.tenantId);
        return config;
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
