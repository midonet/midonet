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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;
import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@ZoomClass(clazz = Topology.QOSPolicy.class)
public class QOSPolicy extends UriResource {

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "bandwidth_limit_rules")
    public List<QOSRuleBWLimit> bandwidthLimitRules;

    @ZoomField(name = "dscp_marking_rules")
    public List<QOSRuleDSCP> dscpMarkingRules;

    @ZoomField(name = "port_ids")
    public List<UUID> portIds;

    @ZoomField(name = "network_ids")
    public List<UUID> networkIds;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.QOS_POLICIES(), id);
    }

    @JsonIgnore
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
        this.bandwidthLimitRules.clear();
        this.dscpMarkingRules.clear();
    }

    @JsonIgnore
    public void update(QOSPolicy from) {
        id = from.id;
        bandwidthLimitRules = from.bandwidthLimitRules;
        dscpMarkingRules = from.dscpMarkingRules;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .omitNullValues()
                .add("id", id)
                .add("bandwidthLimitRules", bandwidthLimitRules)
                .add("dscpMarkingRules", dscpMarkingRules)
                .toString();
    }

}
