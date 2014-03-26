/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.cluster.data.Router.Property;
import org.midonet.util.version.Since;

import javax.validation.GroupSequence;
import javax.validation.constraints.NotNull;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/**
 * Class representing Virtual Router.
 */
@XmlRootElement
public class Router extends UriResource {

    @NotNull(groups = RouterUpdateGroup.class)
    private UUID id;

    @NotNull
    private String tenantId;

    @NotNull
    private String name;

    protected boolean adminStateUp;

    private UUID inboundFilterId;
    private UUID outboundFilterId;

    @Since("2")
    private UUID loadBalancerId;

    /**
     * Constructor.
     */
    public Router() {
        adminStateUp = true;
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
        this();
        this.id = id;
        this.name = name;
        this.tenantId = tenantId;
    }

    /**
     * Router constructor
     *
     * @param routerData
     *            Router data object
     */
    public Router(org.midonet.cluster.data.Router routerData) {
        this(routerData.getId(), routerData.getData().name,
                routerData.getProperty(Property.tenant_id));
        this.adminStateUp = routerData.getData().adminStateUp;
        this.inboundFilterId = routerData.getData().inboundFilter;
        this.outboundFilterId = routerData.getData().outboundFilter;
        this.loadBalancerId = routerData.getData().loadBalancer;
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
     * Get administrative state
     *
     * @return administrative state of the port.
     */

    public boolean isAdminStateUp() {
        return adminStateUp;
    }

    /**
     * Set administrative state
     *
     * @param adminStateUp
     *            administrative state of the port.
     */
    public void setAdminStateUp(boolean adminStateUp) {
        this.adminStateUp = adminStateUp;
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

    public UUID getLoadBalancerId() {
        return loadBalancerId;
    }

    public void setLoadBalancerId(UUID loadBalancerId) {
        this.loadBalancerId = loadBalancerId;
    }

    @Since("2")
    public URI getLoadBalancer() {
        if (getBaseUri() != null && loadBalancerId != null) {
            return ResourceUriBuilder.getLoadBalancer(getBaseUri(), loadBalancerId);
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
     * Convert this object to router data object
     *
     * @return Router data object
     */
    public org.midonet.cluster.data.Router toData() {

        return new org.midonet.cluster.data.Router()
                .setId(this.id)
                .setName(this.name)
                .setAdminStateUp(this.adminStateUp)
                .setInboundFilter(this.inboundFilterId)
                .setOutboundFilter(this.outboundFilterId)
                .setLoadBalancer(this.loadBalancerId)
                .setProperty(Property.tenant_id, this.tenantId);
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return "id=" + id + ", name=" + name +
               ", adminStateUp=" + adminStateUp + ", tenantId=" + tenantId;
    }

    /**
     * Interface used for validating a router on updates.
     */
    public interface RouterUpdateGroup {
    }

    /**
     * Interface used for validating a router on creates.
     */
    public interface RouterCreateGroup {
    }

    /**
     * Interface that defines the ordering of validation groups for router
     * create.
     */
    @GroupSequence({ Default.class, RouterCreateGroup.class })
    public interface RouterCreateGroupSequence {
    }

    /**
     * Interface that defines the ordering of validation groups for router
     * update.
     */
    @GroupSequence({ Default.class, RouterUpdateGroup.class })
    public interface RouterUpdateGroupSequence {
    }

}
