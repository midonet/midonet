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

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;

@ZoomClass(clazz = Neutron.L2GatewayConnection.class)
public class L2GatewayConnection extends ZoomObject {
    @ZoomField(name = "id")
    public UUID id;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @JsonProperty("network_id")
    @ZoomField(name = "network_id")
    public UUID networkId;

    @JsonProperty("segmentation_id")
    @ZoomField(name = "segmentation_id")
    public Integer segmentationId;

    @JsonProperty("l2_gateway")
    @ZoomField(name = "l2_gateway")
    public L2Gateway l2Gateway;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        L2GatewayConnection that = (L2GatewayConnection) o;

        return Objects.equal(id, that.id) &&
               Objects.equal(tenantId, that.tenantId) &&
               Objects.equal(networkId, that.networkId) &&
               Objects.equal(segmentationId, that.segmentationId) &&
               Objects.equal(l2Gateway, that.l2Gateway);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, tenantId, networkId, segmentationId,
                                l2Gateway);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .omitNullValues()
            .add("id", id)
            .add("tenantId", tenantId)
            .add("networkId", networkId)
            .add("segmentationId", segmentationId)
            .add("l2Gateway", l2Gateway)
            .toString();
    }
}
