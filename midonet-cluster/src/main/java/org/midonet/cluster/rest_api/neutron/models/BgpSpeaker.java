/*
 * Copyright 2016 Midokura SARL
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

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;

import static org.apache.commons.collections4.ListUtils.isEqualList;

@ZoomClass(clazz = Neutron.NeutronBgpSpeaker.class)
public class BgpSpeaker extends ZoomObject {
    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @ZoomField(name = "name")
    public String name;

    @JsonProperty("local_as")
    @ZoomField(name = "local_as")
    public Integer localAs;

    @JsonProperty("ip_version")
    @ZoomField(name = "ip_version")
    public Integer ipVersion;

    @JsonProperty("router_id")
    @ZoomField(name = "router_id")
    public UUID routerId;

    @JsonProperty("network_ids")
    @ZoomField(name = "network_ids")
    public List<UUID> networkIds;

    @JsonProperty("bgp_peer_ids")
    @ZoomField(name = "bgp_peer_ids")
    public List<UUID> bgpPeerIds;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        BgpSpeaker that = (BgpSpeaker) o;

        return Objects.equals(id, that.id) &&
               Objects.equals(tenantId, that.tenantId) &&
               Objects.equals(name, that.name) &&
               Objects.equals(localAs, that.localAs) &&
               Objects.equals(ipVersion, that.ipVersion) &&
               Objects.equals(routerId, that.routerId) &&
               isEqualList(networkIds, that.networkIds) &&
               isEqualList(bgpPeerIds, that.bgpPeerIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, tenantId, name, localAs, ipVersion);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("tenantId", tenantId)
            .add("name", name)
            .add("localAs", localAs)
            .add("ipVersion", ipVersion)
            .add("routerId", routerId)
            .add("networkIds", networkIds)
            .add("bgpPeerIds", bgpPeerIds)
            .toString();
    }
}
