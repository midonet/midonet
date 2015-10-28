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

package org.midonet.client.resource;

import java.net.URI;
import java.util.UUID;

import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoBridgePort;

import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_PORT_LINK_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_PORT_V3_JSON;

public class BridgePort extends
        Port<BridgePort, DtoBridgePort> {

    public BridgePort(WebResource resource, URI uriForCreation,
                      DtoBridgePort port) {
        super(resource, uriForCreation, port,
              APPLICATION_PORT_V3_JSON());
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    public UUID getVifId() {
        return principalDto.getVifId();
    }

    public UUID getDeviceId() {
        return principalDto.getDeviceId();
    }

    public UUID getId() {
        return principalDto.getId();
    }

    public boolean isAdminStateUp() {
        return principalDto.isAdminStateUp();
    }

    public UUID getInboundFilterId() {
        return principalDto.getInboundFilterId();
    }

    public UUID getOutboundFilterId() {
        return principalDto.getOutboundFilterId();
    }

    public String getType() {
        return principalDto.getType();
    }

    public UUID getPeerId() {
        return principalDto.getPeerId();
    }

    public Short getVlanId() {
        return principalDto.getVlanId();
    }

    public BridgePort adminStateUp(boolean adminStateUp) {
        principalDto.setAdminStateUp(adminStateUp);
        return this;
    }

    public BridgePort inboundFilterId(UUID id) {
        principalDto.setInboundFilterId(id);
        return this;
    }

    public BridgePort outboundFilterId(UUID id) {
        principalDto.setOutboundFilterId(id);
        return this;
    }

    public BridgePort vifId(UUID id) {
        principalDto.setVifId(id);
        return this;
    }

    public BridgePort link(UUID id) {
        peerId(id);
        resource.post(principalDto.getLink(),
                principalDto, APPLICATION_PORT_LINK_JSON());
        return get(getUri());
    }

    public BridgePort unlink() {
        resource.delete(principalDto.getLink());
        return get(getUri());
    }

    @Override
    public String toString() {
        return String.format("BridgePort{id=%s, type=%s, inboundFilterId=%s," +
                "outboundFilterId=%s}", principalDto.getId(),
                principalDto.getType(), principalDto.getInboundFilterId(),
                principalDto.getOutboundFilterId());
    }

    private BridgePort peerId(UUID id) {
        principalDto.setPeerId(id);
        return this;
    }

    public BridgePort vlanId(Short vlanId) {
        principalDto.setVlanId(vlanId);
        return this;
    }

}
