/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.l4lb;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/* Class representing health monitor info */
@XmlRootElement
public class HealthMonitor extends UriResource {

    private UUID id;
    private String type;
    private UUID poolId;
    private int delay;
    private int timeout;
    private int maxRetries;
    private boolean adminStateUp;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setPoolId(UUID poolId) {
        this.poolId = poolId;
    }

    public UUID getPoolId() {
        return poolId;
    }

    public int getDelay() {
        return delay;
    }

    public void setDelay(int delay) {
        this.delay = delay;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int max_retries) {
        this.maxRetries = max_retries;
    }

    public boolean getAdminStateUp() {
        return adminStateUp;
    }

    public void setAdminStateUp(boolean admin_state_up) {
        this.adminStateUp = admin_state_up;
    }

    public HealthMonitor() {
        super();
    }

    public HealthMonitor(
            org.midonet.cluster.data.l4lb.HealthMonitor healthMonitor) {
        super();
        this.type = healthMonitor.getType();
        this.poolId = healthMonitor.getPoolId();
        this.delay = healthMonitor.getDelay();
        this.timeout = healthMonitor.getTimeout();
        this.adminStateUp = healthMonitor.getAdminStateUp();
        this.maxRetries = healthMonitor.getMaxRetries();
        this.id = healthMonitor.getId();
    }

    public org.midonet.cluster.data.l4lb.HealthMonitor toData() {
        return new org.midonet.cluster.data.l4lb.HealthMonitor()
                .setId(this.id)
                .setType(this.type)
                .setPoolId(this.poolId)
                .setDelay(this.delay)
                .setTimeout(this.timeout)
                .setMaxRetries(this.maxRetries)
                .setAdminStateUp(this.adminStateUp);
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getHealthMonitor(getBaseUri(), id);
        } else {
            return null;
        }
    }
}
