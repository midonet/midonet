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
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.cluster.util.UUIDUtil;

@ZoomClass(clazz = Topology.TunnelZone.class)
public class TunnelZone extends UriResource {

    public static final int MIN_TUNNEL_ZONE_NAME_LEN = 1;
    public static final int MAX_TUNNEL_ZONE_NAME_LEN = 255;

    @ZoomEnum(clazz = Topology.TunnelZone.Type.class)
    public enum TunnelZoneType {
        @ZoomEnumValue(value = "GRE") gre,
        @ZoomEnumValue(value = "VXLAN") vxlan,
        @ZoomEnumValue(value = "VTEP") vtep
    }

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @NotNull
    @ZoomField(name = "name")
    @Size(min = MIN_TUNNEL_ZONE_NAME_LEN, max = MAX_TUNNEL_ZONE_NAME_LEN)
    public String name;

    @NotNull
    @ZoomField(name = "type")
    public TunnelZoneType type;

    @JsonIgnore
    @ZoomField(name = "hosts")
    public List<TunnelZoneHost> tzHosts;
    @JsonIgnore
    @ZoomField(name = "host_ids", converter = UUIDUtil.Converter.class)
    public List<UUID> hostIds;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.TUNNEL_ZONES, id);
    }

    public URI getHosts() {
        return relativeUri(ResourceUris.HOSTS);
    }

    @JsonIgnore
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(TunnelZone from) {
        id = from.id;
        tzHosts = from.tzHosts;
        hostIds = from.hostIds;
    }

    @JsonIgnore
    public void removeHost(UUID hostId) {
        tzHosts.removeIf(new Predicate<TunnelZoneHost>() {
            @Override
            public boolean test(TunnelZoneHost tunnelZoneHost) {
                return Objects.equals(tunnelZoneHost.hostId, hostId);
            }
        });
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
            .add("id", id)
            .add("name", name)
            .add("type", type)
            .add("tzHosts", tzHosts)
            .add("hostIds", hostIds)
            .toString();
    }
}
