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
import org.slf4j.LoggerFactory;

@ZoomClass(clazz = org.midonet.cluster.models.Neutron.NeutronHealthMonitorV2.class)
public class HealthMonitorV2 extends ZoomObject {

    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @JsonProperty("admin_state_up")
    @ZoomField(name = "admin_state_up")
    public Boolean adminStateUp;

    @JsonProperty("pool_id")
    @ZoomField(name = "pool_id")
    public UUID poolId;

    @ZoomField(name = "pools")
    public List<UUID> pools;

    @JsonProperty("expected_codes")
    @ZoomField(name = "expected_codes")
    public String expectedCodes;

    @JsonProperty("http_method")
    @ZoomField(name = "http_method")
    public String httpMethod;

    @JsonProperty("url_path")
    @ZoomField(name = "url_path")
    public String urlPath;

    @ZoomField(name = "delay")
    public Integer delay;

    @JsonProperty("max_retries")
    @ZoomField(name = "max_retries")
    public Integer maxRetries;

    @ZoomField(name = "timeout")
    public Integer timeout;

    @ZoomField(name = "type")
    public HealthMonitorV2Type type;

    @ZoomField(name = "name")
    public String name;

    @ZoomEnum(clazz = org.midonet.cluster.models.Neutron.NeutronHealthMonitorV2.HealthMonitorV2Type.class)
    public enum HealthMonitorV2Type {
        @ZoomEnumValue("HTTP") HTTP,
        @ZoomEnumValue("HTTPS") HTTPS,
        @ZoomEnumValue("PING") PING,
        @ZoomEnumValue("TCP") TCP;

        @JsonCreator
        @SuppressWarnings("unused")
        public static HealthMonitorV2Type forValue(String v) {
            if (v == null) {
                return null;
            }
            try {
                return valueOf(v.toUpperCase());
            } catch (IllegalArgumentException ex) {
                LoggerFactory.getLogger(HealthMonitorV2.class)
                        .warn("Unknown type enum value {}", v);
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
                .add("name", name)
                .add("adminStateUp", adminStateUp)
                .add("pools", pools)
                .add("poolId", poolId)
                .add("expectedCodes", expectedCodes)
                .add("httpMethod", httpMethod)
                .add("urlPath", urlPath)
                .add("delay", delay)
                .add("maxRetries", maxRetries)
                .add("timeout", timeout)
                .add("type", type)
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HealthMonitorV2 that = (HealthMonitorV2) o;
        return Objects.equal(id, that.id) &&
                Objects.equal(tenantId, that.tenantId) &&
                Objects.equal(name, that.name) &&
                Objects.equal(adminStateUp, that.adminStateUp) &&
                Objects.equal(pools, that.pools) &&
                Objects.equal(poolId, that.poolId) &&
                Objects.equal(expectedCodes, that.expectedCodes) &&
                Objects.equal(httpMethod, that.httpMethod) &&
                Objects.equal(urlPath, that.urlPath) &&
                Objects.equal(delay, that.delay) &&
                Objects.equal(maxRetries, that.maxRetries) &&
                Objects.equal(timeout, that.timeout) &&
                Objects.equal(type, that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, tenantId, name, adminStateUp,
                pools, poolId, expectedCodes, httpMethod, urlPath, delay,
                maxRetries, timeout, type);
    }
}
