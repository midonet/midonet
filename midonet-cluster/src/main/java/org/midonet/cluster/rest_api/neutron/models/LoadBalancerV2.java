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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import java.util.List;
import java.util.UUID;

import org.midonet.cluster.data.*;
import org.midonet.cluster.models.Neutron;
import org.slf4j.LoggerFactory;

@ZoomClass(clazz = Neutron.NeutronLoadBalancerV2.class)
public class LoadBalancerV2 extends ZoomObject {

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

    @JsonProperty("provisioning_status")
    @ZoomField(name = "provisioning_status")
    public LoadBalancerV2Status provisioningStatus;

    @JsonProperty("operating_status")
    @ZoomField(name = "operating_status")
    public LoadBalancerV2Status operatingStatus;

    @ZoomField(name = "listeners")
    public List<UUID> listeners;

    @JsonProperty("vip_address")
    @ZoomField(name = "vip_address")
    public String vipAddress;

    @JsonProperty("vip_subnet_id")
    @ZoomField(name = "vip_subnet_id")
    public UUID vipSubnetId;

    @JsonProperty("vip_port_id")
    @ZoomField(name = "vip_port_id")
    public UUID vipPortId;

    @ZoomField(name = "provider")
    public String provider;

    @JsonProperty("flavor_id")
    @ZoomField(name = "flavor_id")
    public UUID flavorId;

    @ZoomField(name = "pools")
    public List<UUID> pools;

    @ZoomEnum(clazz = Neutron.NeutronLoadBalancerV2.LoadBalancerV2Status.class)
    public enum LoadBalancerV2Status {
        @ZoomEnumValue("ACTIVE") ACTIVE,
        @ZoomEnumValue("PENDING_CREATE") PENDING_CREATE,
        @ZoomEnumValue("ERROR") ERROR;

        @JsonCreator
        @SuppressWarnings("unused")
        public static LoadBalancerV2Status forValue(String v) {
            if (v == null) {
                return null;
            }
            try {
                return valueOf(v.toUpperCase());
            } catch (IllegalArgumentException ex) {
                LoggerFactory.getLogger(LoadBalancerV2.class)
                        .warn("Unknown status enum value {}", v);
                return null;
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LoadBalancerV2 that = (LoadBalancerV2) o;
        return Objects.equal(id, that.id) &&
                Objects.equal(tenantId, that.tenantId) &&
                Objects.equal(vipPortId, that.vipPortId) &&
                Objects.equal(name, that.name) &&
                Objects.equal(description, that.description) &&
                Objects.equal(adminStateUp, that.adminStateUp) &&
                Objects.equal(provisioningStatus, that.provisioningStatus) &&
                Objects.equal(operatingStatus, that.operatingStatus) &&
                Objects.equal(listeners, that.listeners) &&
                Objects.equal(pools, that.pools) &&
                Objects.equal(vipAddress, that.vipAddress) &&
                Objects.equal(vipSubnetId, that.vipSubnetId) &&
                Objects.equal(provider, that.provider) &&
                Objects.equal(flavorId, that.flavorId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, tenantId, vipPortId, name, description,
                adminStateUp, provisioningStatus, operatingStatus, listeners,
                vipAddress, vipSubnetId, provider, flavorId, pools);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .omitNullValues()
                .add("id", id)
                .add("tenantId", tenantId)
                .add("vipPortId", vipPortId)
                .add("name", name)
                .add("description", description)
                .add("adminStateUp", adminStateUp)
                .add("provisioningStatus", provisioningStatus)
                .add("operatingStatus", operatingStatus)
                .add("listeners", listeners)
                .add("pools", pools)
                .add("vipAddress", vipAddress)
                .add("vipSubnetId", vipSubnetId)
                .add("provider", provider)
                .add("flavorId", flavorId)
                .toString();
    }
}
