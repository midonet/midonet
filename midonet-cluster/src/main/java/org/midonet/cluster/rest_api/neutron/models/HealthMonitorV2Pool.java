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
import org.midonet.cluster.models.Neutron;

import java.util.UUID;

@ZoomClass(clazz = Neutron.NeutronHealthMonitorV2.HealthMonitorV2Pool.class)
public class HealthMonitorV2Pool extends ZoomObject {

    public HealthMonitorV2Pool() {}

    public HealthMonitorV2Pool(UUID poolId, String status,
                               String statusDescription) {
        this.poolId = poolId;
        this.status = status;
        this.statusDescription = statusDescription;
    }

    @JsonProperty("pool_id")
    @ZoomField(name = "pool_id")
    public UUID poolId;

    @ZoomField(name = "status")
    public String status;

    @JsonProperty("status_description")
    @ZoomField(name = "status_description")
    public String statusDescription;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HealthMonitorV2Pool that = (HealthMonitorV2Pool) o;
        return Objects.equal(poolId, that.poolId) &&
                Objects.equal(status, that.status) &&
                Objects.equal(statusDescription, that.statusDescription);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(poolId, status, statusDescription);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .omitNullValues()
                .add("poolId", poolId)
                .add("status", status)
                .add("statusDescription", statusDescription)
                .toString();
    }
}
