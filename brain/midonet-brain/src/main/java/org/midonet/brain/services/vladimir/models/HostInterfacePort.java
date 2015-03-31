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
package org.midonet.brain.services.vladimir.models;

import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;

// TODO: @IsHostInterfaceUnused(groups = HostInterfacePort.HostInterfacePortCreateGroup.class)
// doesn't support direct translation to zoom
@XmlRootElement
public class HostInterfacePort {

    @NotNull
    // TODO: @IsHostIdInAnyTunnelZone(groups = HostInterfacePortCreateGroup.class)
    public UUID hostId;

    // TODO: @IsValidPortId
    @NotNull
    public UUID portId;

    @NotNull(groups = HostInterfacePortCreateGroup.class)
    public String interfaceName;

    // This group is used for validating the create process in which
    // the interface name must be provided.
    public interface HostInterfacePortCreateGroup extends Default {
    }
}
