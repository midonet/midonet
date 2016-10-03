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

package org.midonet.cluster.rest_api.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ZoomClass(clazz = Topology.QOSPolicy.class)
public class QOSPolicy extends UriResource {

    @ZoomClass(clazz = Topology.QOSPolicy.QOSRule.class)
    public static class QOSRule extends ZoomObject {

        public static String QOS_RULE_TYPE_BW_LIMIT = "bandwidth_limit";
        public static String QOS_RULE_TYPE_DSCP = "dscp_marking";

        @ZoomField(name = "id")
        public UUID id;

        @ZoomField(name = "type")
        public String type;

        @ZoomField(name = "max_kbps")
        @JsonProperty("max_kbps")
        public Integer maxKbps = null;

        @ZoomField(name = "max_burst_kbps")
        @JsonProperty("max_burst_kbps")
        public Integer maxBurstKbps = null;

        @ZoomField(name = "dscp_mark")
        @JsonProperty("dscp_mark")
        public Integer dscpMark = null;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            QOSRule qosRule = (QOSRule) o;

            if (!id.equals(qosRule.id)) return false;
            if (!type.equals(qosRule.type)) return false;
            if (maxKbps != null ?
                    !maxKbps.equals(qosRule.maxKbps) :
                    qosRule.maxKbps != null)
                return false;
            if (maxBurstKbps != null ?
                    !maxBurstKbps.equals(qosRule.maxBurstKbps) :
                    qosRule.maxBurstKbps != null)
                return false;
            return dscpMark != null ?
                    dscpMark.equals(qosRule.dscpMark) :
                    qosRule.dscpMark == null;

        }

        @Override
        public int hashCode() {
            int result = id.hashCode();
            result = 31 * result + type.hashCode();
            result = 31 * result + (maxKbps != null ? maxKbps.hashCode() : 0);
            result = 31 * result + (maxBurstKbps != null ? maxBurstKbps.hashCode() : 0);
            result = 31 * result + (dscpMark != null ? dscpMark.hashCode() : 0);
            return result;
        }
    }

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "name")
    public String name;

    @ZoomField(name = "description")
    public String description;

    @ZoomField(name = "shared")
    public Boolean shared;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public UUID tenantId;

    @JsonIgnore
    @ZoomField(name = "bandwidth_limit_rule_ids")
    public List<UUID> bandwidthLimitRuleIds;

    @JsonIgnore
    @ZoomField(name = "dscp_marking_rule_ids")
    public List<UUID> dscpMarkingRuleIds;

    @ZoomField(name = "rules")
    public List<QOSRule> rules = new ArrayList<>();

    @JsonIgnore
    @ZoomField(name = "port_ids")
    public List<UUID> portIds;

    @JsonIgnore
    @ZoomField(name = "network_ids")
    public List<UUID> networkIds;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.QOS_POLICIES(), id);
    }

    public URI getBwLimitRules() {
        return relativeUri(ResourceUris.QOS_BW_LIMIT_RULES());
    }

    public URI getDscpRules() {
        return relativeUri(ResourceUris.QOS_DSCP_RULES());
    }

    @Override
    @JsonIgnore
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(QOSPolicy from) {
        id = from.id;
        bandwidthLimitRuleIds = from.bandwidthLimitRuleIds;
        dscpMarkingRuleIds = from.dscpMarkingRuleIds;
        portIds = from.portIds;
        networkIds = from.networkIds;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .omitNullValues()
                .add("id", id)
                .add("name", name)
                .add("description", description)
                .add("shared", shared)
                .add("bandwidthLimitRuleIds", bandwidthLimitRuleIds)
                .add("dscpMarkingRuleIds", dscpMarkingRuleIds)
                .toString();
    }

}
