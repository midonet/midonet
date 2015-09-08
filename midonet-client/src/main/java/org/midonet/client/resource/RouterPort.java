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

package org.midonet.client.resource;

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.MultivaluedMap;

import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoBgp;
import org.midonet.client.dto.DtoRouterPort;

import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_BGP_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_PORT_LINK_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_PORT_V2_JSON;

public class RouterPort extends Port<RouterPort, DtoRouterPort> {


    public RouterPort(WebResource resource, URI uriForCreation,
                      DtoRouterPort p) {
        super(resource, uriForCreation, p, APPLICATION_PORT_V2_JSON());
    }

    @Override
    public URI getUri() {
        return principalDto.getUri();
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

    public String getNetworkAddress() {
        return principalDto.getNetworkAddress();
    }

    public int getNetworkLength() {
        return principalDto.getNetworkLength();
    }

    public UUID getOutboundFilterId() {
        return principalDto.getOutboundFilterId();
    }

    public String getPortAddress() {
        return principalDto.getPortAddress();
    }

    public String getPortMac() {
        return principalDto.getPortMac();
    }

    public String getType() {
        return principalDto.getType();
    }

    public UUID getVifId() {
        return principalDto.getVifId();
    }

    public UUID getPeerId() {
        return principalDto.getPeerId();
    }

    public RouterPort adminStateUp(boolean adminStateUp) {
        principalDto.setAdminStateUp(adminStateUp);
        return this;
    }

    public RouterPort networkLength(int networkLength) {
        principalDto.setNetworkLength(networkLength);
        return this;
    }

    public RouterPort outboundFilterId(UUID outboundFilterId) {
        principalDto.setOutboundFilterId(outboundFilterId);
        return this;
    }

    public RouterPort portAddress(String portAddress) {
        principalDto.setPortAddress(portAddress);
        return this;
    }

    public RouterPort vifId(UUID vifId) {
        principalDto.setVifId(vifId);
        return this;
    }

    public RouterPort portMac(String portMac) {
        principalDto.setPortMac(portMac);
        return this;
    }

    public RouterPort inboundFilterId(UUID inboundFilterId) {
        principalDto.setInboundFilterId(inboundFilterId);
        return this;
    }

    public RouterPort networkAddress(String networkAddress) {
        principalDto.setNetworkAddress(networkAddress);
        return this;
    }

    public RouterPort peerId(UUID id) {
        principalDto.setPeerId(id);
        return this;
    }

    public ResourceCollection<Bgp> getBgps(
            MultivaluedMap<String,String> queryParams) {
        return getChildResources(
            principalDto.getBgps(),
            queryParams,
            APPLICATION_BGP_COLLECTION_JSON(),
            Bgp.class, DtoBgp.class);
    }

    public Bgp addBgp() {
        return new Bgp(resource,
                       principalDto.getBgps(),
                       new DtoBgp());
    }

    // TODO(pino): this should be defined in a interior port subtype.
    public RouterPort link(UUID id) {
        peerId(id);
        resource.post(principalDto.getLink(),
                      principalDto,
                      APPLICATION_PORT_LINK_JSON());
        get(getUri());
        return this;
    }

    public RouterPort unlink() {
        resource.delete(principalDto.getLink());
        get(getUri());
        return this;
    }

    @Override
    public String toString() {
        return String.format("RouterPort{id=%s, type=%s}", principalDto.getId(),
                             principalDto.getType());
    }
}
