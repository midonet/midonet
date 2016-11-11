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
import org.midonet.cluster.models.Commons;
import org.midonet.cluster.models.Neutron;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;
import org.midonet.cluster.data.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

@ZoomClass(clazz = Neutron.NeutronLoadBalancerV2Pool.class)
public class PoolV2 extends ZoomObject {

    @ZoomField(name = "admin_state_up")
    public UUID id;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @JsonProperty("project_id")
    @ZoomField(name = "project_id")
    public String projectId;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "description")
    public String description;

    @JsonProperty("admin_state_up")
    @ZoomField(name = "admin_state_up")
    public Boolean adminStateUp;

    @ZoomEnum(clazz = Neutron.NeutronLoadBalancerV2Pool.LBAlgorithm.class)
    public enum LBAlgorithm {
        @ZoomEnumValue("ROUND_ROBIN") ROUND_ROBIN,
        @ZoomEnumValue("LEAST_CONNECTIONS") LEAST_CONNECTIONS,
        @ZoomEnumValue("SOURCE_IP") SOURCE_IP;

        @JsonCreator
        @SuppressWarnings("unused")
        public static LBAlgorithm forValue(String v) {
            if (v == null) {
                return null;
            }
            return valueOf(v.toUpperCase());
        }
    }

    @JsonProperty("lb_algorithm")
    @ZoomField(name = "lb_algorithm")
    public LBAlgorithm lbAlgorithm;

    @ZoomField(name = "members")
    public List<UUID> members;

    @JsonProperty("subnet_id")
    @ZoomField(name = "subnet_id")
    public UUID subnetId;

    @JsonProperty("vip_id")
    @ZoomField(name = "vip_id")
    public UUID vipId;

    @JsonProperty("health_monitors")
    @ZoomField(name = "health_monitors")
    public List<UUID> healthMonitors;


    @JsonProperty("health_monitors_status")
    @ZoomField(name = "health_monitors_status")
    public List<HealthMonitorV2Status> healthMonitorsStatus;

    @ZoomField(name = "protocol")
    public Commons.Protocol protocol;

    @ZoomField(name = "status")
    public Commons.LBStatus status;

    @ZoomField(name = "provider")
    public String provider;

    @JsonProperty("session_persistence")
    @ZoomField(name = "session_persistence")
    public LBV2SessionPersistenceAlgorithm sessionPersistence;

    public PoolV2() {}

    public PoolV2(UUID id, String tenantId, UUID subnetId, String name,
                  Commons.Protocol protocol, LBAlgorithm lbAlgorithm,
                  Boolean adminStateUp) {
        this.id = id;
        this.tenantId = tenantId;
        this.subnetId = subnetId;
        this.name = name;
        this.protocol = protocol;
        this.lbAlgorithm = lbAlgorithm;
        this.adminStateUp = adminStateUp;
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
        return healthMonitors != null && healthMonitors.size() > 0;
    }

    @JsonIgnore
    public UUID getHealthMonitor() {
        return hasHealthMonitorAssociated() ? healthMonitors.get(0) : null;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .omitNullValues()
                .add("id", id)
                .add("tenantId", tenantId)
                .add("projectId", projectId)
                .add("name", name)
                .add("description", description)
                .add("adminStateUp", adminStateUp)
                .add("lbAlgorithm", lbAlgorithm)
                .add("members", members)
                .add("subnetId", subnetId)
                .add("vipId", vipId)
                .add("healthMonitors", healthMonitors)
                .add("healthMonitorsStatus", healthMonitorsStatus)
                .add("protocol", protocol)
                .add("status", status)
                .add("provider", provider)
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
                Objects.equal(projectId, poolV2.projectId) &&
                Objects.equal(name, poolV2.name) &&
                Objects.equal(description, poolV2.description) &&
                Objects.equal(adminStateUp, poolV2.adminStateUp) &&
                lbAlgorithm == poolV2.lbAlgorithm &&
                Objects.equal(members, poolV2.members) &&
                Objects.equal(subnetId, poolV2.subnetId) &&
                Objects.equal(vipId, poolV2.vipId) &&
                Objects.equal(healthMonitors, poolV2.healthMonitors) &&
                Objects.equal(healthMonitorsStatus, poolV2.healthMonitorsStatus) &&
                protocol == poolV2.protocol &&
                status == poolV2.status &&
                Objects.equal(provider, poolV2.provider) &&
                Objects.equal(sessionPersistence, poolV2.sessionPersistence);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, tenantId, projectId, name, description,
                adminStateUp, lbAlgorithm, members, subnetId, vipId,
                healthMonitors, healthMonitorsStatus, protocol, status,
                provider, sessionPersistence);
    }
}
