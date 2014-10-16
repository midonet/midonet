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

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;
import org.midonet.api.network.validation.IsValidPortId;
import org.midonet.api.network.validation.PortsLinkable;

import javax.validation.GroupSequence;
import javax.validation.groups.Default;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.UUID;

/**
 * Port link DTO
 */
@PortsLinkable(groups = Link.LinkCreateGroupExtended.class)
@XmlRootElement
public class Link extends UriResource {

    @IsValidPortId
    private UUID portId;

    @IsValidPortId
    private UUID peerId;

    public Link() {
    }

    public Link(UUID portId, UUID peerId) {
        this.portId = portId;
        this.peerId = peerId;
    }

    public UUID getPortId() {
        return this.portId;
    }

    public void setPortId(UUID portId) {
        this.portId = portId;
    }

    public UUID getPeerId() {
        return this.peerId;
    }

    public void setPeerId(UUID peerId) {
        this.peerId = peerId;
    }

    public URI getPort() {
        if (getBaseUri() != null && portId != null) {
            return ResourceUriBuilder.getPort(getBaseUri(), portId);
        } else {
            return null;
        }
    }

    public URI getPeer() {
        if (getBaseUri() != null && peerId != null) {
            return ResourceUriBuilder.getPort(getBaseUri(), peerId);
        } else {
            return null;
        }
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && portId != null) {
            return ResourceUriBuilder.getPortLink(getBaseUri(), portId);
        } else {
            return null;
        }
    }

    /**
     * Interface used for validating a link on create.
     */
    public interface LinkCreateGroupExtended {
    }


    /**
     * Interface that defines the ordering of validation groups for link
     * create.
     */
    @GroupSequence({ Default.class, LinkCreateGroupExtended.class})
    public interface LinkCreateGroupSequence {
    }

}
