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

package org.midonet.cluster.rest_api.neutron.models;
import com.google.common.base.MoreObjects;
import org.midonet.cluster.models.Neutron;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.midonet.cluster.data.*;

@ZoomClass(clazz = Neutron.NeutronLoadBalancerV2Pool.class)
public class PoolV2 extends ZoomObject {

    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "description")
    public String description;

    @JsonProperty("admin_state_up")
    @ZoomField(name = "admin_state_up")
    public Boolean adminStateUp;

    @JsonProperty("lb_algorithm")
    @ZoomField(name = "lb_algorithm")
    public String lbAlgorithm;

    @ZoomField(name = "members")
    public List<UUID> members;

    @JsonProperty("healthmonitor_id")
    @ZoomField(name = "healthmonitor_id")
    public UUID healthMonitorId;

    @JsonProperty("listener_id")
    @ZoomField(name = "listener_id")
    public UUID listenerId;

    @ZoomField(name = "listeners")
    public List<UUID> listeners;

    @ZoomField(name = "protocol")
    public String protocol;

    @JsonProperty("loadbalancers")
    @ZoomField(name = "loadbalancers")
    public List<UUID> loadBalancers;

    @JsonProperty("session_persistence")
    @ZoomField(name = "session_persistence")
    public LBV2SessionPersistenceAlgorithm sessionPersistence;

    public PoolV2() {}

    public PoolV2(UUID id, String tenantId, String name,
                  String protocol, String lbAlgorithm,
                  Boolean adminStateUp) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.protocol = protocol;
        this.lbAlgorithm = lbAlgorithm;
        this.adminStateUp = adminStateUp;
        this.listeners = new ArrayList<>();
        this.loadBalancers = new ArrayList<>();
    }

    @JsonIgnore
    public void addMember(UUID memberId) {
        if (members == null) {
            members = new ArrayList<>();
        }
        members.add(memberId);
    }

    @JsonIgnore
    public void removeMember(UUID memberId) {
        if (members == null) {
            members = new ArrayList<>();
            return;
        }
        members.remove(memberId);
    }

    @JsonIgnore
    public boolean hasHealthMonitorAssociated() {
        return healthMonitorId != null;
    }

    @JsonIgnore
    public UUID getHealthMonitor() {
        return healthMonitorId;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .omitNullValues()
                .add("id", id)
                .add("tenantId", tenantId)
                .add("name", name)
                .add("description", description)
                .add("adminStateUp", adminStateUp)
                .add("lbAlgorithm", lbAlgorithm)
                .add("members", members)
                .add("healthMonitorId", healthMonitorId)
                .add("protocol", protocol)
                .add("listenerId", listenerId)
                .add("listeners", listeners)
                .add("loadBalancers", loadBalancers)
                .add("sessionPersistence", sessionPersistence)
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PoolV2 poolV2 = (PoolV2) o;
        return Objects.equal(id, poolV2.id) &&
                Objects.equal(tenantId, poolV2.tenantId) &&
                Objects.equal(name, poolV2.name) &&
                Objects.equal(description, poolV2.description) &&
                Objects.equal(adminStateUp, poolV2.adminStateUp) &&
                Objects.equal(lbAlgorithm, poolV2.lbAlgorithm) &&
                Objects.equal(members, poolV2.members) &&
                Objects.equal(listeners, poolV2.listeners) &&
                Objects.equal(healthMonitorId, poolV2.healthMonitorId) &&
                Objects.equal(protocol, poolV2.protocol) &&
                Objects.equal(listenerId, poolV2.listenerId) &&
                Objects.equal(loadBalancers, poolV2.loadBalancers) &&
                Objects.equal(sessionPersistence, poolV2.sessionPersistence);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, tenantId, name, description,
                adminStateUp, lbAlgorithm, members, healthMonitorId, protocol,
                listeners, listenerId, loadBalancers, sessionPersistence);
    }
}
