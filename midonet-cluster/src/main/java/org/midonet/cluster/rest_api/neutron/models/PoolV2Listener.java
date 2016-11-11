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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Commons;
import org.midonet.cluster.models.Neutron;

import java.util.List;
import java.util.UUID;

@ZoomClass(clazz = Neutron.NeutronLoadBalancerV2Listener.class)
public class PoolV2Listener extends ZoomObject {

    @JsonProperty("max_retries")
    @ZoomField(name = "max_retries")
    public UUID id;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @JsonProperty("project_id")
    @ZoomField(name = "project_id")
    public String projectId;

    @JsonProperty("connection_limit")
    @ZoomField(name = "connection_limit")
    public Integer connectionLimit;

    @ZoomField(name = "protocol")
    public Commons.Protocol protocol;

    @ZoomField(name = "description")
    public String description;

    @JsonProperty("admin_state_up")
    @ZoomField(name = "admin_state_up")
    public Boolean adminStateUup;

    @JsonProperty("default_tls_container_ref")
    @ZoomField(name = "default_tls_container_ref")
    public String defaultTlsContainerRef;

    @JsonProperty("sni_container_refs")
    @ZoomField(name = "sni_container_refs")
    public List<String> sniContainerRefs;

    @ZoomField(name = "loadbalancers")
    public List<UUID> loadbalancers;

    @JsonProperty("default_pool_id")
    @ZoomField(name = "default_pool_id")
    public UUID defaultPoolId;

    @ZoomField(name = "name")
    public String name;

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .omitNullValues()
                .add("id", id)
                .add("tenantId", tenantId)
                .add("projectId", projectId)
                .add("name", name)
                .add("description", description)
                .add("connectionLimit", connectionLimit)
                .add("defaultTlsContainerRef", defaultTlsContainerRef)
                .add("sniContainerRefs", sniContainerRefs)
                .add("loadbalancers", loadbalancers)
                .add("defaultPoolId", defaultPoolId)
                .add("protocol", protocol)
                .toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PoolV2Listener that = (PoolV2Listener) o;
        return Objects.equal(id, that.id) &&
                Objects.equal(tenantId, that.tenantId) &&
                Objects.equal(projectId, that.projectId) &&
                Objects.equal(connectionLimit, that.connectionLimit) &&
                protocol == that.protocol &&
                Objects.equal(description, that.description) &&
                Objects.equal(adminStateUup, that.adminStateUup) &&
                Objects.equal(defaultTlsContainerRef, that.defaultTlsContainerRef) &&
                Objects.equal(sniContainerRefs, that.sniContainerRefs) &&
                Objects.equal(loadbalancers, that.loadbalancers) &&
                Objects.equal(defaultPoolId, that.defaultPoolId) &&
                Objects.equal(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, tenantId, projectId, connectionLimit,
                protocol, description, adminStateUup, defaultTlsContainerRef,
                sniContainerRefs, loadbalancers, defaultPoolId, name);
    }
}
