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

import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.annotation.ParentId;
import org.midonet.cluster.rest_api.annotation.Resource;
import org.midonet.cluster.rest_api.annotation.ResourceId;
import org.midonet.cluster.util.UUIDUtil;

// TODO: @IsHostInterfaceUnused(groups = HostInterfacePort.HostInterfacePortCreateGroup.class)
@XmlRootElement
@Resource(name = ResourceUris.PORTS, parents = { Host.class })
@ZoomClass(clazz = Topology.Port.class)
public class HostInterfacePort extends UriResource {

    // TODO: @IsValidPortId
    @NotNull
    @ResourceId
    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    public UUID portId;

    @NotNull
    @ParentId
    @ZoomField(name = "host_id", converter = UUIDUtil.Converter.class)
    // TODO: @IsHostIdInAnyTunnelZone(groups = HostInterfacePortCreateGroup.class)
    public UUID hostId;

    @NotNull(groups = HostInterfacePortCreateGroup.class)
    @ZoomField(name = "interface_name")
    public String interfaceName;

    // This group is used for validating the create process in which
    // the interface name must be provided.
    public interface HostInterfacePortCreateGroup extends Default {
    }
}
