/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.client.dto;

import com.google.common.base.Objects;
import org.codehaus.jackson.annotate.JsonIgnore;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoHealthMonitor {
    private UUID id;
    private String type;
    private int delay;
    private int timeout;
    private int maxRetries;
    private boolean adminStateUp = true;
    private LBStatus status = LBStatus.ACTIVE;
    private URI uri;
    private URI pools;

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

    public LBStatus getStatus() {
        return status;
    }

    @JsonIgnore
    public void setStatus(LBStatus status) {
        this.status = status;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public URI getPools() {
        return pools;
    }

    public void setPools(URI pools) {
        this.pools = pools;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoHealthMonitor that = (DtoHealthMonitor) o;

        return Objects.equal(id, that.getId()) &&
                Objects.equal(type, that.getType()) &&
                delay == that.getDelay() &&
                timeout == that.getTimeout() &&
                maxRetries == that.getMaxRetries() &&
                adminStateUp == that.isAdminStateUp() &&
                status == that.getStatus();
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, type, delay, timeout, maxRetries,
                adminStateUp, status);
    }
}
