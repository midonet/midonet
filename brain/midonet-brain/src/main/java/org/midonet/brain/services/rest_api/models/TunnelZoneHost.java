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
package org.midonet.brain.services.rest_api.models;

import java.util.UUID;

import javax.validation.GroupSequence;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.packets.IPv4;

// TODO: @IsUniqueTunnelZoneMember(groups = TunnelZoneHostUnique.class)
// TODO: not used in ZOOM
@XmlRootElement
public class TunnelZoneHost {

    // TODO: @IsValidTunnelZoneId
    private UUID tunnelZoneId;

    // TODO: @IsValidHostId
    private UUID hostId;

    @NotNull
    @Pattern(regexp = IPv4.regex, message = "is an invalid IP format")
    private String ipAddress;

    /**
     * Interface used for validating a tunnel zone on creates.
     */
    public interface TunnelZoneHostUnique { }

    /**
     * Interface that defines the ordering of validation groups for tunnel zone
     * create.
     */
    @GroupSequence({ Default.class, TunnelZoneHostUnique.class })
    public interface TunnelZoneHostCreateGroupSequence { }
}
