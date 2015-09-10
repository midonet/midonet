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

import com.google.common.base.Objects;
import org.midonet.client.dto.l4lb.PoolLBMethod;
import org.midonet.client.dto.l4lb.LBStatus;
import org.midonet.client.dto.l4lb.PoolProtocol;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoPool {
    private UUID id;
    private URI uri;
    private UUID loadBalancerId;
    private URI loadBalancer;
    private UUID healthMonitorId;
    private URI healthMonitor;
    private URI vips;
    private URI poolMembers;
    private PoolProtocol protocol = PoolProtocol.TCP;
    private PoolLBMethod lbMethod = PoolLBMethod.ROUND_ROBIN;
    private boolean adminStateUp = true;
    private LBStatus status = LBStatus.ACTIVE;
    private PoolHealthMonitorMappingStatus mappingStatus =
            PoolHealthMonitorMappingStatus.INACTIVE;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public UUID getLoadBalancerId() {
        return loadBalancerId;
    }

    public void setLoadBalancerId(UUID loadBalancerId) {
        this.loadBalancerId = loadBalancerId;
    }

    public URI getLoadBalancer() {
        return loadBalancer;
    }

    public void setLoadBalancer(URI loadBalancer) {
        this.loadBalancer = loadBalancer;
    }

    public UUID getHealthMonitorId() {
        return healthMonitorId;
    }

    public void setHealthMonitorId(UUID healthMonitorId) {
        this.healthMonitorId = healthMonitorId;
    }

    public URI getHealthMonitor() {
        return healthMonitor;
    }

    public void setHealthMonitor(URI healthMonitor) {
        this.healthMonitor = healthMonitor;
    }

    public URI getVips() {
        return vips;
    }

    public void setVips(URI vips) {
        this.vips = vips;
    }

    public URI getPoolMembers() {
        return poolMembers;
    }

    public void setPoolMembers(URI poolMembers) {
        this.poolMembers = poolMembers;
    }

    public PoolProtocol getProtocol() {
        return protocol;
    }

    public void setProtocol(PoolProtocol protocol) {
        this.protocol = protocol;
    }

    public PoolLBMethod getLbMethod() {
        return lbMethod;
    }

    public void setLbMethod(PoolLBMethod lbMethod) {
        this.lbMethod = lbMethod;
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

    public void setStatus(LBStatus status) {
        this.status = status;
    }

    public PoolHealthMonitorMappingStatus getMappingStatus() {
        return mappingStatus;
    }

    public void setMappingStatus(PoolHealthMonitorMappingStatus mappingStatus) {
        this.mappingStatus = mappingStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoPool that = (DtoPool) o;

        return Objects.equal(id, that.getId()) &&
                Objects.equal(loadBalancerId,
                        that.getLoadBalancerId()) &&
                Objects.equal(healthMonitorId,
                        that.getHealthMonitorId()) &&
                Objects.equal(protocol, that.getProtocol()) &&
                Objects.equal(lbMethod, that.getLbMethod()) &&
                adminStateUp == that.isAdminStateUp() &&
                status == that.getStatus() &&
                Objects.equal(mappingStatus,
                        that.getMappingStatus());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, loadBalancerId, healthMonitorId, protocol,
                lbMethod, adminStateUp, status, mappingStatus);
    }
}
