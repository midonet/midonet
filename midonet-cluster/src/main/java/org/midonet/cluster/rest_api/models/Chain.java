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

import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;

@ZoomClass(clazz = Topology.Chain.class)
public class Chain extends UriResource {

    public static final int MIN_CHAIN_NAME_LEN = 0;
    public static final int MAX_CHAIN_NAME_LEN = 255;

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "tenant_id")
    public String tenantId;

    @Size(min = MIN_CHAIN_NAME_LEN, max = MAX_CHAIN_NAME_LEN)
    @ZoomField(name = "name")
    public String name;

    @JsonIgnore
    @ZoomField(name = "rule_ids")
    public List<UUID> ruleIds;

    @JsonIgnore
    @ZoomField(name = "network_inbound_ids")
    public List<UUID> networkInboundIds;

    @JsonIgnore
    @ZoomField(name = "network_outbound_ids")
    public List<UUID> networkOutboundIds;

    @JsonIgnore
    @ZoomField(name = "router_inbound_ids")
    public List<UUID> routerInboundIds;

    @JsonIgnore
    @ZoomField(name = "router_outbound_ids")
    public List<UUID> routerOutboundIds;

    @JsonIgnore
    @ZoomField(name = "port_inbound_ids")
    public List<UUID> portInboundIds;

    @JsonIgnore
    @ZoomField(name = "port_outbound_ids")
    public List<UUID> portOutboundIds;

    @JsonIgnore
    @ZoomField(name = "jump_rule_ids")
    public List<UUID> jumpRuleIds;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.CHAINS, id);
    }

    public URI getRules() {
        return relativeUri(ResourceUris.RULES);
    }

    @JsonIgnore
    @Override
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("tenantId", tenantId)
            .add("name", name)
            .add("ruleIds", ruleIds)
            .add("networkInboundIds", networkInboundIds)
            .add("networkOutboundIds", networkOutboundIds)
            .add("routerInboundIds", routerInboundIds)
            .add("routerOutboundIds", routerOutboundIds)
            .add("portInboundIds", portInboundIds)
            .add("portOutboundIds", portOutboundIds)
            .add("jumpRuleIds", jumpRuleIds)
            .toString();
    }
}
