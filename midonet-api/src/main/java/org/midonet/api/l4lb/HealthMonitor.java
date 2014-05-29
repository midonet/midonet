/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.l4lb;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.api.validation.VerifyEnumValue;
import org.midonet.midolman.state.l4lb.HealthMonitorType;
import org.midonet.midolman.state.l4lb.LBStatus;

import java.net.URI;
import java.util.UUID;
import javax.validation.constraints.NotNull;
import javax.xml.bind.annotation.XmlRootElement;

/* Class representing health monitor info */
@XmlRootElement
public class HealthMonitor extends UriResource {

    private UUID id;
    @NotNull
    @VerifyEnumValue(HealthMonitorType.class)
    private String type;
    private int delay;
    private int timeout;
    private int maxRetries;
    private boolean adminStateUp = true;
    @VerifyEnumValue(LBStatus.class)
    private String status = LBStatus.ACTIVE.toString();

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

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
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

    @JsonIgnore
    public void setStatus(String status) {
        this.status = status;
    }

    public HealthMonitor() {
        super();
    }

    public HealthMonitor(
            org.midonet.cluster.data.l4lb.HealthMonitor healthMonitor) {
        super();
        this.id = healthMonitor.getId();
        this.type = healthMonitor.getType().toString();
        this.delay = healthMonitor.getDelay();
        this.timeout = healthMonitor.getTimeout();
        this.maxRetries = healthMonitor.getMaxRetries();
        this.adminStateUp = healthMonitor.isAdminStateUp();
        this.status = healthMonitor.getStatus().toString();
    }

    public org.midonet.cluster.data.l4lb.HealthMonitor toData() {
        return new org.midonet.cluster.data.l4lb.HealthMonitor()
                .setId(this.id)
                .setType(HealthMonitorType.valueOf(this.type))
                .setDelay(this.delay)
                .setTimeout(this.timeout)
                .setMaxRetries(this.maxRetries)
                .setAdminStateUp(this.adminStateUp)
                .setStatus(Enum.valueOf(LBStatus.class, this.status));
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

    public URI getPools() {
        return (getBaseUri() == null || id == null) ? null :
                ResourceUriBuilder.getHealthMonitorPools(getBaseUri(), id);
    }
}
