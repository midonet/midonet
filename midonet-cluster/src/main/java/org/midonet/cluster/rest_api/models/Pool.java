/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.midolman.state.l4lb.PoolLBMethod;
import org.midonet.midolman.state.l4lb.PoolProtocol;

@ZoomClass(clazz = Topology.Pool.class)
public class Pool extends UriResource {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @ZoomField(name = "admin_state_up")
    public boolean adminStateUp = true;

    @ZoomField(name = "health_monitor_id", converter = UUIDUtil.Converter.class)
    public UUID healthMonitorId;

    @NotNull
    @ZoomField(name = "load_balancer_id", converter = UUIDUtil.Converter.class)
    public UUID loadBalancerId;

    @ZoomField(name = "protocol")
    public PoolProtocol protocol;

    @NotNull
    @ZoomField(name = "lb_method")
    public PoolLBMethod lbMethod;

    @ZoomField(name = "status")
    public LBStatus status;

    @JsonIgnore
    @ZoomField(name = "pool_member_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> poolMemberIds;

    @JsonIgnore
    @ZoomField(name = "vip_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> vipIds;

    @JsonIgnore
    @ZoomField(name = "mapping_status")
    public PoolHealthMonitorMappingStatus mappingStatus;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.POOLS, id);
    }

    public URI getHealthMonitor() {
        return absoluteUri(ResourceUris.HEALTH_MONITORS, healthMonitorId);
    }

    public URI getLoadBalancer() {
        return absoluteUri(ResourceUris.LOAD_BALANCERS, loadBalancerId);
    }

    public URI getVips() {
        return relativeUri(ResourceUris.VIPS);
    }

    public URI getPoolMembers() {
        return relativeUri(ResourceUris.POOL_MEMBERS);
    }

    @Override
    @JsonIgnore
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
        protocol = PoolProtocol.TCP;
        status = LBStatus.ACTIVE;
        if (this.healthMonitorId != null) {
            mappingStatus = PoolHealthMonitorMappingStatus.PENDING_CREATE;
        } else {
            mappingStatus = PoolHealthMonitorMappingStatus.ACTIVE;
        }
    }

    @JsonIgnore
    public void create(UUID loadBalancerId) {
        create();
        this.loadBalancerId = loadBalancerId;
    }

    @JsonIgnore
    public void update(Pool from) {
        id = from.id;
        poolMemberIds = from.poolMemberIds;
        vipIds = from.vipIds;
        if (!Objects.equals(this.healthMonitorId, from.healthMonitorId)) {
            mappingStatus = PoolHealthMonitorMappingStatus.PENDING_CREATE;
        } else {
            mappingStatus = PoolHealthMonitorMappingStatus.ACTIVE;
        }
        // Disallow changing status from the API, but don't fail
        status = from.status;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("adminStateUp", adminStateUp)
            .add("healthMonitorId", healthMonitorId)
            .add("loadBalancerId", loadBalancerId)
            .add("protocol", protocol)
            .add("lbMethod", lbMethod)
            .add("status", status)
            .add("poolMemberIds", poolMemberIds)
            .add("vipIds", vipIds)
            .toString();
    }
}
