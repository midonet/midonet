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
package org.midonet.api.network;

import org.midonet.api.UriResource;
import org.midonet.api.network.validation.IsValidPortId;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.network.validation.IsValidPortGroupId;

import javax.validation.GroupSequence;
import javax.validation.constraints.NotNull;
import javax.validation.groups.Default;
import java.net.URI;
import java.util.UUID;

/**
 * DTO to represent a membership of a port in a port group.
 */
public class PortGroupPort extends UriResource {

    @NotNull
    @IsValidPortGroupId(groups = PortGroupPortCreateExtended.class)
    private UUID portGroupId;

    @NotNull
    @IsValidPortId(groups = PortGroupPortCreateExtended.class)
    private UUID portId;

    public PortGroupPort(){
    }

    public UUID getPortGroupId() {
        return portGroupId;
    }

    public void setPortGroupId(UUID portGroupId) {
        this.portGroupId = portGroupId;
    }

    public UUID getPortId() {
        return portId;
    }

    public void setPortId(UUID portId) {
        this.portId = portId;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && portGroupId != null && portId != null) {
            return ResourceUriBuilder.getPortGroupPort(getBaseUri(),
                    portGroupId, portId);
        } else {
            return null;
        }
    }

    public URI getPort() {
        if (getBaseUri() != null && portId != null) {
            return ResourceUriBuilder.getPort(getBaseUri(), portId);
        } else {
            return null;
        }
    }

    public URI getPortGroup() {
        if (getBaseUri() != null && portGroupId != null) {
            return ResourceUriBuilder.getPortGroup(getBaseUri(), portGroupId);
        } else {
            return null;
        }
    }

    public interface PortGroupPortCreateExtended {
    }

    @GroupSequence({ Default.class, PortGroupPortCreateExtended.class})
    public interface PortGroupPortCreateGroupSequence {
    }


}
