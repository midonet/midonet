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
import com.fasterxml.jackson.annotation.JsonCreator;
import com.google.common.base.Objects;
import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Neutron;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.midonet.cluster.data.ZoomEnum;

import java.util.List;
import java.util.UUID;

@ZoomClass(clazz = Neutron.NeutronLoadBalancerV2.class)
public class LoadBalancerV2 {

    @ZoomField(name = "id")
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

    @ZoomEnum(clazz = Neutron.NeutronLoadBalancerV2.ProvisioningStatus.class)
    private enum ProvisioningStatus {
        @ZoomEnumValue("ACTIVE") ACTIVE,
        @ZoomEnumValue("PENDING_CREATE") PENDING_CREATE,
        @ZoomEnumValue("ERROR") ERROR;

        @JsonCreator
        @SuppressWarnings("unused")
        public static ProvisioningStatus forValue(String v) {
            if (v == null) {
                return null;
            }
            return valueOf(v.toUpperCase());
        }
    }

    @JsonProperty("provisioning_status")
    @ZoomField(name = "provisioning_status")
    public ProvisioningStatus provisioningStatus;

    @ZoomEnum(clazz = Neutron.NeutronLoadBalancerV2.OperatingStatus.class)
    public enum OperatingStatus {
        @ZoomEnumValue("ONLINE") ONLINE,
        @ZoomEnumValue("OFFLINE") OFFLINE;

        @JsonCreator
        @SuppressWarnings("unused")
        public static OperatingStatus forValue(String v) {
            if (v == null) {
                return null;
            }
            return valueOf(v.toUpperCase());
        }
    }

    @JsonProperty("operating_status")
    @ZoomField(name = "operating_status")
    public OperatingStatus operatingStatus;

    @ZoomField(name = "listeners")
    public List<UUID> listeners;

    @JsonProperty("vip_address")
    @ZoomField(name = "vip_address")
    public String vip_address;

    @JsonProperty("vip_subnet_id")
    @ZoomField(name = "vip_subnet_id")
    public UUID vip_subnet_id;

    @ZoomField(name = "provider")
    public String provider;

    @ZoomField(name = "flavor")
    public UUID flavor;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LoadBalancerV2 that = (LoadBalancerV2) o;
        return Objects.equal(id, that.id) &&
                Objects.equal(tenantId, that.tenantId) &&
                Objects.equal(projectId, that.projectId) &&
                Objects.equal(name, that.name) &&
                Objects.equal(description, that.description) &&
                Objects.equal(adminStateUp, that.adminStateUp) &&
                provisioningStatus == that.provisioningStatus &&
                operatingStatus == that.operatingStatus &&
                Objects.equal(listeners, that.listeners) &&
                Objects.equal(vip_address, that.vip_address) &&
                Objects.equal(vip_subnet_id, that.vip_subnet_id) &&
                Objects.equal(provider, that.provider) &&
                Objects.equal(flavor, that.flavor);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, tenantId, projectId, name, description,
                adminStateUp, provisioningStatus, operatingStatus, listeners,
                vip_address, vip_subnet_id, provider, flavor);
    }

    @Override
    public String toString() {
        return "LoadBalancerV2{" +
                "id=" + id +
                ", tenantId='" + tenantId + '\'' +
                ", projectId='" + projectId + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", adminStateUp=" + adminStateUp +
                ", provisioningStatus=" + provisioningStatus +
                ", operatingStatus=" + operatingStatus +
                ", listeners=" + listeners +
                ", vip_address='" + vip_address + '\'' +
                ", vip_subnet_id=" + vip_subnet_id +
                ", provider='" + provider + '\'' +
                ", flavor=" + flavor +
                '}';
    }
}
