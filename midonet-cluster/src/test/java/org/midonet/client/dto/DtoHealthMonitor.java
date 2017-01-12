/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.client.dto;

import java.net.URI;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Objects;

import org.midonet.client.dto.l4lb.HealthMonitorType;
import org.midonet.client.dto.l4lb.LBStatus;

public class DtoHealthMonitor {
    private UUID id;
    private HealthMonitorType type;
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

    public HealthMonitorType getType() {
        return type;
    }

    public void setType(HealthMonitorType type) {
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
                type == that.getType() &&
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
