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
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.annotation.Resource;
import org.midonet.cluster.rest_api.annotation.ResourceId;
import org.midonet.cluster.rest_api.annotation.Subresource;
import org.midonet.cluster.util.UUIDUtil;

@XmlRootElement
@Resource(name = ResourceUris.TUNNEL_ZONES)
@ZoomClass(clazz = Topology.TunnelZone.class)
public class TunnelZone extends UriResource {

    @ZoomEnum(clazz = Topology.TunnelZone.Type.class)
    public enum TunnelZoneType {
        @ZoomEnumValue(value = "GRE") gre,
        @ZoomEnumValue(value = "VXLAN") vxlan,
        @ZoomEnumValue(value = "VTEP") vtep
    }

    @NotNull
    @ResourceId
    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID id;

    @NotNull
    @Size(min = 1, max = 255)
    // TODO: @UniqueTunnelZoneName
    @ZoomField(name = "name")
    public String name;

    // TODO: fix this
    @NotNull
    // TODO: @AllowedValue(values = { TunnelZoneType.GRE, TunnelZoneType.VxLAN, TunnelZoneType.VTEP })
    @ZoomField(name = "type")
    public TunnelZoneType type;

    @XmlTransient
    @Subresource(name = ResourceUris.HOSTS)
    @ZoomField(name = "hosts")
    public List<TunnelZoneHost> hosts;

    public URI getHosts() {
        return relativeUri(ResourceUris.HOSTS);
    }

}
