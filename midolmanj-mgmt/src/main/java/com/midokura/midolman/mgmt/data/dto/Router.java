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

import com.midokura.midolman.mgmt.data.dto.Router.RouterExtended;
import com.midokura.midolman.mgmt.data.dto.config.RouterNameMgmtConfig;
import com.midokura.midolman.mgmt.jaxrs.validation.annotation.IsUniqueRouterName;
import com.midokura.midolman.mgmt.jaxrs.ResourceUriBuilder;
import com.midokura.midolman.state.zkManagers.RouterZkManager.RouterConfig;

/**
 * Class representing Virtual Router.
 */
@IsUniqueRouterName(groups = RouterExtended.class)
@XmlRootElement
public class Router extends UriResource {

    public static final int MIN_ROUTER_NAME_LEN = 1;
    public static final int MAX_ROUTER_NAME_LEN = 255;

    private UUID id;
    private String tenantId;
    private UUID inboundFilterId;
    private UUID outboundFilterId;

    @NotNull
    @Size(min = MIN_ROUTER_NAME_LEN, max = MAX_ROUTER_NAME_LEN)
    private String name;

    /**
     * Constructor.
     */
    public Router() {
    }

    /**
     * Constructor
     *
     * @param id
     *            ID of the router.
     * @param name
     *            Name of the router.
     * @param tenantId
     *            ID of the tenant that owns the router.
     */
    public Router(UUID id, String name, String tenantId) {
        super();
        this.id = id;
        this.name = name;
        this.tenantId = tenantId;
    }

    /**
     * Router constructor
     *
     * @param id
     *            Router ID
     * @param config
     *            RouterConfig object
     */
    public Router(UUID id, RouterConfig config) {
        this(id, config.name, config.properties.get(ConfigProperty.TENANT_ID));
        this.inboundFilterId = config.inboundFilter;
        this.outboundFilterId = config.outboundFilter;
    }

    /**
     * Get router ID.
     *
     * @return Router ID.
     */
    public UUID getId() {
        return id;
    }

    /**
     * Set router ID.
     *
     * @param id
     *            ID of the router.
     */
    public void setId(UUID id) {
        this.id = id;
    }

    /**
     * Get router name.
     *
     * @return Router name.
     */
    public String getName() {
        return name;
    }

    /**
     * Set router name.
     *
     * @param name
     *            Name of the router.
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
     *            Tenant ID of the router.
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
     * @return the ports URI.
     */
    public URI getPorts() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getRouterPorts(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the peer ports URI
     */
    public URI getPeerPorts() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getRouterPeerPorts(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * @return the routes URI.
     */
    public URI getRoutes() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getRouterRoutes(getBaseUri(), id);
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
            return ResourceUriBuilder.getRouter(getBaseUri(), id);
        } else {
            return null;
        }
    }

    /**
     * Convert this object to RouterConfig object
     *
     * @return RouterConfig object
     */
    public RouterConfig toConfig() {
        RouterConfig config = new RouterConfig(this.name, this.inboundFilterId,
                this.outboundFilterId);
        config.properties.put(ConfigProperty.TENANT_ID, this.tenantId);
        return config;
    }

    /**
     * Convert this object to RouterNameMgmtConfig object.
     *
     * @return RouterNameMgmtConfig object.
     */
    public RouterNameMgmtConfig toNameMgmtConfig() {
        return new RouterNameMgmtConfig(this.getId());
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
    public interface RouterExtended {
    }

    /**
     * Interface that defines the ordering of validation groups.
     */
    @GroupSequence({ Default.class, RouterExtended.class })
    public interface RouterGroupSequence {
    }

}
