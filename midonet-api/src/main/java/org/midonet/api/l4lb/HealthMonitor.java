/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.l4lb;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/* Class representing health monitor info */
@XmlRootElement
public class HealthMonitor extends UriResource {

    public final String TYPE_PATTERN = "TCP";
    public final String STATUS_PATTERN = "ACTIVE|DOWN|" +
            "PENDING_CREATE|PENDING_UPDATE|PENDING_DELETE|" +
            "INACTIVE|ERROR";

    private UUID id;
    @Pattern(regexp = TYPE_PATTERN,
             message = "is not in the pattern (" + TYPE_PATTERN + ")")
    private String type;
    @NotNull
    private int delay;
    @NotNull
    private int timeout;
    @NotNull
    private int maxRetries;
    private boolean adminStateUp = true;
    @Pattern(regexp = STATUS_PATTERN,
             message = "is not in the pattern (" + STATUS_PATTERN + ")")
    private String status;

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
        this.type = healthMonitor.getType();
        this.delay = healthMonitor.getDelay();
        this.timeout = healthMonitor.getTimeout();
        this.maxRetries = healthMonitor.getMaxRetries();
        this.adminStateUp = healthMonitor.isAdminStateUp();
        this.status = healthMonitor.getStatus();
    }

    public org.midonet.cluster.data.l4lb.HealthMonitor toData() {
        return new org.midonet.cluster.data.l4lb.HealthMonitor()
                .setId(this.id)
                .setType(this.type)
                .setDelay(this.delay)
                .setTimeout(this.timeout)
                .setMaxRetries(this.maxRetries)
                .setAdminStateUp(this.adminStateUp)
                .setStatus(this.status);
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
