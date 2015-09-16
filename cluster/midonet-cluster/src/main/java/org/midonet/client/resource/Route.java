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
import org.midonet.client.dto.DtoRoute;

import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_ROUTE_JSON;

public class Route extends ResourceBase<Route, DtoRoute> {


    public Route(WebResource resource, URI uriForCreation, DtoRoute route) {
        super(resource, uriForCreation, route, APPLICATION_ROUTE_JSON());

    }

    public URI getUri() {
        return principalDto.getUri();
    }

    public String getAttributes() {
        return principalDto.getAttributes();
    }

    public String getDstNetworkAddr() {
        return principalDto.getDstNetworkAddr();
    }

    public int getDstNetworkLength() {
        return principalDto.getDstNetworkLength();
    }

    public UUID getId() {
        return principalDto.getId();
    }

    public String getNextHopGateway() {
        return principalDto.getNextHopGateway();
    }

    public UUID getNextHopPort() {
        return principalDto.getNextHopPort();
    }

    public UUID getRouterId() {
        return principalDto.getRouterId();
    }

    public String getSrcNetworkAddr() {
        return principalDto.getSrcNetworkAddr();
    }

    public int getSrcNetworkLength() {
        return principalDto.getSrcNetworkLength();
    }

    public String getType() {
        return principalDto.getType();
    }


    public int getWeight() {
        return principalDto.getWeight();
    }

    public Route type(String type) {
        principalDto.setType(type);
        return this;
    }

    public Route nextHopGateway(String nextHopGateway) {
        principalDto.setNextHopGateway(nextHopGateway);
        return this;
    }

    public Route dstNetworkAddr(String dstNetworkAddr) {
        principalDto.setDstNetworkAddr(dstNetworkAddr);
        return this;
    }

    public Route weight(int weight) {
        principalDto.setWeight(weight);
        return this;
    }

    public Route nextHopPort(UUID nextHopPort) {
        principalDto.setNextHopPort(nextHopPort);
        return this;
    }

    public Route dstNetworkLength(int dstNetworkLength) {
        principalDto.setDstNetworkLength(dstNetworkLength);
        return this;
    }

    public Route srcNetworkLength(int srcNetworkLength) {
        principalDto.setSrcNetworkLength(srcNetworkLength);
        return this;
    }

    public Route srcNetworkAddr(String srcNetworkAddr) {
        principalDto.setSrcNetworkAddr(srcNetworkAddr);
        return this;
    }

    @Override
    public String toString() {
        return String.format("Route{id=%s}", principalDto.getId());
    }
}
