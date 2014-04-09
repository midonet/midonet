/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.l4lb;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.api.validation.VerifyEnumValue;
import org.midonet.midolman.state.l4lb.PoolLBMethod;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.midolman.state.l4lb.PoolProtocol;

import java.net.URI;
import java.util.UUID;
import javax.validation.constraints.NotNull;
import javax.xml.bind.annotation.XmlRootElement;

/* Class representing pool info */
@XmlRootElement
public class Pool extends UriResource {

    private UUID id;
    private UUID healthMonitorId;
    @NotNull
    private UUID loadBalancerId;
    @VerifyEnumValue(PoolProtocol.class)
    private String protocol = PoolProtocol.TCP.toString();
    @NotNull
    @VerifyEnumValue(PoolLBMethod.class)
    private String lbMethod;
    private boolean adminStateUp = true;
    @VerifyEnumValue(LBStatus.class)
    private String status = LBStatus.ACTIVE.toString();

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    @JsonIgnore
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
        this.loadBalancerId = pool.getLoadBalancerId();
        this.healthMonitorId = pool.getHealthMonitorId();
        this.protocol = pool.getProtocol().toString();
        this.lbMethod = pool.getLbMethod().toString();
        this.adminStateUp = pool.isAdminStateUp();
        this.status = pool.getStatus().toString();
        this.id = pool.getId();
    }

    public org.midonet.cluster.data.l4lb.Pool toData() {
        return new org.midonet.cluster.data.l4lb.Pool()
                .setId(this.id)
                .setLoadBalancerId(this.loadBalancerId)
                .setHealthMonitorId(this.healthMonitorId)
                .setProtocol(PoolProtocol.valueOf(this.protocol))
                .setLbMethod(PoolLBMethod.valueOf(this.lbMethod))
                .setAdminStateUp(this.adminStateUp)
                .setStatus(LBStatus.valueOf(this.status));
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
