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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import org.slf4j.LoggerFactory;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;

@ZoomClass(clazz = Neutron.NeutronLoadBalancerV2Listener.class)
public class ListenerV2 extends ZoomObject {

    @JsonProperty("id")
    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @JsonProperty("connection_limit")
    @ZoomField(name = "connection_limit")
    public Integer connectionLimit;

    @ZoomField(name = "protocol")
    public ListenerV2Protocol protocol;

    @ZoomField(name = "description")
    public String description;

    @JsonProperty("admin_state_up")
    @ZoomField(name = "admin_state_up")
    public Boolean adminStateUp;

    @JsonProperty("default_tls_container_ref")
    @ZoomField(name = "default_tls_container_ref")
    public String defaultTlsContainerRef;

    @JsonProperty("sni_container_refs")
    @ZoomField(name = "sni_container_refs")
    public List<String> sniContainerRefs = new ArrayList<>();

    @JsonProperty("loadbalancers")
    @ZoomField(name = "loadbalancers")
    public List<UUID> loadBalancers = new ArrayList<>();

    @JsonProperty("default_pool_id")
    @ZoomField(name = "default_pool_id")
    public UUID defaultPoolId;

    @ZoomField(name = "name")
    public String name;

    @JsonProperty("protocol_port")
    @ZoomField(name = "protocol_port")
    public Integer protocolPort;

    @ZoomEnum(clazz = Neutron.NeutronLoadBalancerV2Listener.ListenerV2Protocol.class)
    public enum ListenerV2Protocol {
        @ZoomEnumValue("HTTP") HTTP,
        @ZoomEnumValue("HTTPS") HTTPS,
        @ZoomEnumValue("HTTPS_TERMINATED") HTTPS_TERMINATED,
        @ZoomEnumValue("TCP") TCP;

        @JsonCreator
        @SuppressWarnings("unused")
        public static ListenerV2Protocol forValue(String v) {
            if (v == null) {
                return null;
            }
            try {
                return valueOf(v.toUpperCase());
            } catch (IllegalArgumentException ex) {
                LoggerFactory.getLogger(ListenerV2.class)
                        .warn("Unknown protocol enum value {}", v);
                return null;
            }
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .omitNullValues()
                .add("id", id)
                .add("tenantId", tenantId)
                .add("protocolPort", protocolPort)
                .add("name", name)
                .add("description", description)
                .add("connectionLimit", connectionLimit)
                .add("adminStateUp", adminStateUp)
                .add("defaultTlsContainerRef", defaultTlsContainerRef)
                .add("sniContainerRefs", sniContainerRefs)
                .add("loadBalancers", loadBalancers)
                .add("defaultPoolId", defaultPoolId)
                .add("protocol", protocol)
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ListenerV2 that = (ListenerV2) o;
        return Objects.equal(id, that.id) &&
                Objects.equal(tenantId, that.tenantId) &&
                Objects.equal(protocolPort, that.protocolPort) &&
                Objects.equal(connectionLimit, that.connectionLimit) &&
                Objects.equal(protocol, that.protocol) &&
                Objects.equal(description, that.description) &&
                Objects.equal(adminStateUp, that.adminStateUp) &&
                Objects.equal(defaultTlsContainerRef, that.defaultTlsContainerRef) &&
                Objects.equal(sniContainerRefs, that.sniContainerRefs) &&
                Objects.equal(loadBalancers, that.loadBalancers) &&
                Objects.equal(defaultPoolId, that.defaultPoolId) &&
                Objects.equal(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, tenantId, protocolPort, connectionLimit,
                protocol, description, adminStateUp, defaultTlsContainerRef,
                sniContainerRefs, loadBalancers, defaultPoolId, name);
    }
}
