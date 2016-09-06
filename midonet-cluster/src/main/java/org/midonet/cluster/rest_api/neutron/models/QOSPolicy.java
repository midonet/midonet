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
import org.apache.commons.collections4.ListUtils;
import org.midonet.cluster.data.*;
import org.midonet.cluster.models.Neutron;
import org.midonet.util.collection.ListUtil;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@ZoomClass(clazz = Neutron.NeutronQOSPolicy.class)
public class QOSPolicy extends ZoomObject {

    public QOSPolicy() {}

    public QOSPolicy(UUID qosId) {
        this.id = qosId;
    }

    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("bandwidth_limit_rules")
    @ZoomField(name = "bandwidth_limit_rules")
    public List<QOSRuleBWLimit>  bandwidthLimitRules;

    @JsonProperty("bandwidth_limit_rules")
    @ZoomField(name = "dscp_marking_rules")
    public List<QOSRuleDSCP> dscpMarkingRules;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        QOSPolicy that = (QOSPolicy) o;

        return Objects.equals(id, that.id) &&
                ListUtils.isEqualList(bandwidthLimitRules,
                        that.bandwidthLimitRules) &&
                ListUtils.isEqualList(dscpMarkingRules,
                        that.dscpMarkingRules);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id,
                ListUtils.hashCodeForList(bandwidthLimitRules),
                ListUtils.hashCodeForList(dscpMarkingRules));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("bandwidthLimitRules", ListUtil.toString(bandwidthLimitRules))
            .add("dscpMarkingRules", ListUtil.toString(dscpMarkingRules))
            .toString();
    }
}
