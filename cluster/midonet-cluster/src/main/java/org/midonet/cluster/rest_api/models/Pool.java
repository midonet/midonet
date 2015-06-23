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
import java.util.UUID;

import javax.validation.constraints.NotNull;

import org.codehaus.jackson.annotate.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.UUIDUtil;
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
        // Disallow changing status from the API, but don't fail
        status = from.status;
    }

}
