/*
 * Copyright 2014 Midokura SARL
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

@ZoomClass(clazz = Neutron.NeutronRouter.ExternalGatewayInfo.class)
public class ExternalGatewayInfo extends ZoomObject {

    public ExternalGatewayInfo() {}

    public ExternalGatewayInfo(UUID networkId, Boolean enableSnat) {
        this.networkId = networkId;
        this.enableSnat = enableSnat;
    }

    @JsonProperty("network_id")
    @ZoomField(name = "network_id")
    public UUID networkId;

    @JsonProperty("enable_snat")
    @ZoomField(name = "enable_snat")
    public boolean enableSnat;

    @Override
    public boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof ExternalGatewayInfo)) return false;

        final ExternalGatewayInfo other = (ExternalGatewayInfo) obj;

        return Objects.equal(networkId, other.networkId)
                && Objects.equal(enableSnat, other.enableSnat);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(networkId, enableSnat);
    }

    @Override
    public String toString() {

        return MoreObjects.toStringHelper(this)
                .add("networkId", networkId)
                .add("enableSnat", enableSnat).toString();
    }
}
