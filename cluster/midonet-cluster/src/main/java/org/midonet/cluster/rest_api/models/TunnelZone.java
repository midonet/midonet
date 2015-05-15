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

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import javax.xml.bind.annotation.XmlTransient;

import com.google.common.base.Objects;
import org.codehaus.jackson.annotate.JsonIgnore;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.UUIDUtil;

@ZoomClass(clazz = Topology.TunnelZone.class)
public class TunnelZone extends UriResource {

    public static final int MIN_TUNNEL_ZONE_NAME_LEN = 1;
    public static final int MAX_TUNNEL_ZONE_NAME_LEN = 255;

    @ZoomEnum(clazz = Topology.TunnelZone.Type.class)
    public enum TunnelZoneType {
        @ZoomEnumValue(value = "GRE") gre,
        @ZoomEnumValue(value = "VXLAN") vxlan,
        @ZoomEnumValue(value = "VTEP") vtep;
    }

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    private UUID id;

    @NotNull
    @ZoomField(name = "name")
    @Size(min = MIN_TUNNEL_ZONE_NAME_LEN, max = MAX_TUNNEL_ZONE_NAME_LEN)
    private String name;

    @NotNull
    @ZoomField(name = "type")
    private TunnelZoneType type;

    @XmlTransient
    @ZoomField(name = "hosts")
    private List<TunnelZoneHost> hosts;
    @JsonIgnore
    @ZoomField(name = "host_ids", converter = UUIDUtil.Converter.class)
    private List<UUID> hostIds;

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.TUNNEL_ZONES, id);
    }

    public URI getHosts() {
        return relativeUri(ResourceUris.HOSTS);
    }


    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public TunnelZoneType getType() {
        return type;
    }

    public void setType(TunnelZoneType type) {
        this.type = type;
    }

    public void setTunnelZoneHosts(List<TunnelZoneHost> hosts) {
        this.hosts = hosts;
    }

    public List<TunnelZoneHost> getTunnelZoneHosts() {
        return this.hosts;
    }

    public List<UUID> getHostIds() {
        return hostIds;
    }

    public void setHostIds(List<UUID> hostIds) {
        this.hostIds = hostIds;
    }

    @JsonIgnore
    public void create() {
        if (null == id) {
            id = UUID.randomUUID();
        }
    }

    @JsonIgnore
    public void update(TunnelZone from) {
        this.id = from.id;
        hosts = from.hosts;
        hostIds = from.hostIds;
    }

    public static class TunnelZoneData extends TunnelZone {
        private URI hosts;
        private URI uri;

        @Override
        public URI getUri() {
            return uri;
        }

        public void setUri(URI uri) {
            this.uri = uri;
        }

        @Override
        public URI getHosts() {
            return this.hosts;
        }

        public void setHosts(URI hosts) {
            this.hosts = hosts;
        }

        public TunnelZoneHost addTunnelZoneHost() {
            return new TunnelZoneHost(resource, principalDto.getHosts(),
                    new DtoTunnelZoneHost(), tunnelZoneHostMediaType);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            TunnelZoneData that = (TunnelZoneData) o;

            return super.equals(o) &&
                Objects.equal(this.hosts, that.hosts) &&
                Objects.equal(this.uri, that.uri);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(super.hashCode(), hosts, uri);
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                .add("TunnelZone", super.toString())
                .add("hosts", hosts)
                .add("uri", uri)
                .toString();
        }
    }
}
