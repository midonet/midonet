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

import java.net.URI;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;

@ZoomClass(clazz = Topology.QosRuleBandwidthLimit.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class QosRuleBandwidthLimit extends UriResource {

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "max_kbps")
    public int maxKbps;

    @ZoomField(name = "max_burst_kbps")
    public Integer maxBurstKbps;

    @ZoomField(name = "policy_id")
    public UUID policyId;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.QOS_BW_LIMIT_RULES(), id);
    }

    @JsonIgnore
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
        policyId = null;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .omitNullValues()
                .add("id", id)
                .add("maxKbps", maxKbps)
                .add("maxBurstKbps", maxBurstKbps)
                .add("policyId", policyId)
                .toString();
    }

}
