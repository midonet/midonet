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

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;


@ZoomClass(clazz = Neutron.FirewallLog.class)
public class FirewallLog extends ZoomObject {

    @ZoomField(name = "id")
    public UUID id;

    @ZoomField(name = "description")
    public String description;

    @JsonProperty("tenant_id")
    @ZoomField(name = "tenant_id")
    public String tenantId;

    @JsonProperty("logging_resource_id")
    @ZoomField(name = "logging_resource_id")
    public UUID loggingResourceId;

    @JsonProperty("logging_resource")
    @ZoomField(name = "logging_resource")
    public LoggingResource loggingResource;

    @JsonProperty("fw_event")
    @ZoomField(name = "fw_event")
    public LogEvent fwEvent;

    @JsonProperty("firewall_id")
    @ZoomField(name = "firewall_id")
    public UUID firewallId;

    @ZoomField(name = "firewall")
    public Firewall firewall;

    @Override
    public final boolean equals(Object obj) {

        if (obj == this) return true;

        if (!(obj instanceof FirewallLog)) return false;
        final FirewallLog other = (FirewallLog) obj;

        return Objects.equal(id, other.id)
               && Objects.equal(description, other.tenantId)
               && Objects.equal(tenantId, other.tenantId)
               && Objects.equal(loggingResourceId, other.loggingResourceId)
               && Objects.equal(loggingResource, other.loggingResource)
               && Objects.equal(fwEvent, other.fwEvent)
               && Objects.equal(firewallId, other.firewallId)
               && Objects.equal(firewall, other.firewall);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id, description, tenantId, loggingResourceId,
                                loggingResource, fwEvent, firewallId, firewall);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("id", id)
            .add("description", description)
            .add("tenantId", tenantId)
            .add("loggingResourceId", loggingResourceId)
            .add("loggingResource", loggingResource)
            .add("fwEvent", fwEvent)
            .add("firewallId", firewallId)
            .add("firewall", firewall)
            .toString();
    }
}
