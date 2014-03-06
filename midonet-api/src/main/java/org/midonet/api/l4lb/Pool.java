/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.l4lb;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;

import javax.validation.constraints.NotNull;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/* Class representing pool info */
@XmlRootElement
public class Pool extends UriResource {

    private UUID id;
    private String name;
    private String description;
    private UUID healthMonitorId;
    @NotNull
    private UUID loadBalancerId;
    private String protocol;
    private String lbMethod;
    private boolean adminStateUp = true;
    private String status;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getHealthMonitorId() {
        return healthMonitorId;
    }

    public void setHealthMonitorId(UUID healthMonitorId) {
        this.healthMonitorId = healthMonitorId;
    }

    public UUID getLoadBalancerId() {
        return loadBalancerId;
    }

    public void setLoadBalancerId(UUID loadBalancerId) {
        this.loadBalancerId = loadBalancerId;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getLbMethod() {
        return lbMethod;
    }

    public void setLbMethod(String lbMethod) {
        this.lbMethod = lbMethod;
    }

    public boolean isAdminStateUp() {
        return adminStateUp;
    }

    public void setAdminStateUp(boolean adminStateUp) {
        this.adminStateUp = adminStateUp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Pool() {
        super();
    }

    public Pool(org.midonet.cluster.data.l4lb.Pool pool) {
        super();
        this.name = pool.getName();
        this.description = pool.getDescription();
        this.loadBalancerId = pool.getLoadBalancerId();
        this.healthMonitorId = pool.getHealthMonitorId();
        this.protocol = pool.getProtocol();
        this.lbMethod = pool.getLbMethod();
        this.adminStateUp = pool.isAdminStateUp();
        this.status = pool.getStatus();
        this.id = pool.getId();
    }

    public org.midonet.cluster.data.l4lb.Pool toData() {
        return new org.midonet.cluster.data.l4lb.Pool()
                .setId(this.id)
                .setName(this.name)
                .setDescription(this.description)
                .setLoadBalancerId(this.loadBalancerId)
                .setHealthMonitorId(this.healthMonitorId)
                .setProtocol(this.protocol)
                .setLbMethod(this.lbMethod)
                .setAdminStateUp(this.adminStateUp)
                .setStatus(this.status);
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getPool(getBaseUri(), id);
        } else {
            return null;
        }
    }

    public URI getHealthMonitor() {
        return (getBaseUri() == null || healthMonitorId == null) ? null :
                ResourceUriBuilder.getHealthMonitor(getBaseUri(), healthMonitorId);
    }

    public URI getLoadBalancer() {
        return (getBaseUri() == null || loadBalancerId == null) ? null :
                ResourceUriBuilder.getLoadBalancer(getBaseUri(), loadBalancerId);
    }

    public URI getVips() {
        return (getBaseUri() == null || id == null) ? null :
                ResourceUriBuilder.getPoolVips(getBaseUri(), id);
    }

    public URI getPoolMembers() {
        return (getBaseUri() == null || id == null) ? null :
                ResourceUriBuilder.getPoolPoolMembers(getBaseUri(), id);
    }
}
