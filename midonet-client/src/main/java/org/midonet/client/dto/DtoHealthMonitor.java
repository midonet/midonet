/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.util.UUID;

@XmlRootElement
public class DtoHealthMonitor {
    private UUID id;
    private String type;
    private UUID poolId;
    private int delay;
    private int timeout;
    private int maxRetries;
    private boolean adminStateUp = true;

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

    public UUID getPoolId() {
        return poolId;
    }

    public void setPoolId(UUID poolId) {
        this.poolId = poolId;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoHealthMonitor that = (DtoHealthMonitor) o;

        if (adminStateUp != that.adminStateUp) return false;
        if (delay != that.delay) return false;
        if (maxRetries != that.maxRetries) return false;
        if (timeout != that.timeout) return false;
        if (poolId != null ? !poolId.equals(that.poolId) : that.poolId != null) return false;
        if (type != null ? !type.equals(that.type) : that.type != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = type != null ? type.hashCode() : 0;
        result = 31 * result + (poolId != null ? poolId.hashCode() : 0);
        result = 31 * result + delay;
        result = 31 * result + timeout;
        result = 31 * result + maxRetries;
        result = 31 * result + (adminStateUp ? 1 : 0);
        return result;
    }
}
