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

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoRoute;

public class Route extends ResourceBase<Route, DtoRoute> {


    public Route(WebResource resource, URI uriForCreation, DtoRoute route) {
        super(resource, uriForCreation, route,
                VendorMediaType.APPLICATION_ROUTE_JSON);

    }

    /**
     * Gets URI of this resource.
     *
     * @return URI of this resource
     */
    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    /**
     * Gets attributes of this route.
     *
     * @return attribute
     */
    public String getAttributes() {
        return principalDto.getAttributes();
    }

    /**
     * Gets destination network address.
     *
     * @return destination network address
     */
    public String getDstNetworkAddr() {
        return principalDto.getDstNetworkAddr();
    }

    /**
     * Gets destination network length.
     *
     * @return length for the destination network
     */
    public int getDstNetworkLength() {
        return principalDto.getDstNetworkLength();
    }

    /**
     * Gets ID of this resource
     *
     * @return UUID of this resource
     */
    public UUID getId() {
        return principalDto.getId();
    }

    /**
     * Gets next of gateway address.
     *
     * @return next hope gateway address
     */
    public String getNextHopGateway() {
        return principalDto.getNextHopGateway();
    }

    /**
     * Gets next hope port id.
     *
     * @return UUID of the next hop port
     */
    public UUID getNextHopPort() {
        return principalDto.getNextHopPort();
    }

    /**
     * Gets id of the router for this route.
     *
     * @return router ID
     */
    public UUID getRouterId() {
        return principalDto.getRouterId();
    }

    /**
     * Gets source network address for this route.
     *
     * @return source network address
     */
    public String getSrcNetworkAddr() {
        return principalDto.getSrcNetworkAddr();
    }

    /**
     * Gets source network length.
     *
     * @return source network length
     */
    public int getSrcNetworkLength() {
        return principalDto.getSrcNetworkLength();
    }

    /**
     * Gets type of the route.
     *
     * @return type
     */
    public String getType() {
        return principalDto.getType();
    }


    /**
     * Gets weight of the route.
     *
     * @return weight
     */
    public int getWeight() {
        return principalDto.getWeight();
    }

    /**
     * Sets type for creation.
     *
     * @param type type of the route. THis should be one of normal, blackhole,
     *             or reject.
     * @return this
     */
    public Route type(String type) {
        principalDto.setType(type);
        return this;
    }

    /**
     * Sets next hop gateway address for creation.
     *
     * @param nextHopGateway ip address of the next hop
     * @return this
     */
    public Route nextHopGateway(String nextHopGateway) {
        principalDto.setNextHopGateway(nextHopGateway);
        return this;
    }

    /**
     * Sets destination network address for creation.
     *
     * @param dstNetworkAddr network address of the destination
     * @return this
     */
    public Route dstNetworkAddr(String dstNetworkAddr) {
        principalDto.setDstNetworkAddr(dstNetworkAddr);
        return this;
    }

    /**
     * Sets weight for creation.
     *
     * @param weight positive integer value
     * @return this
     */
    public Route weight(int weight) {
        principalDto.setWeight(weight);
        return this;
    }

    /**
     * Sets next hope port for creation.
     *
     * @param nextHopPort id of the next hop interior port
     * @return this
     */
    public Route nextHopPort(UUID nextHopPort) {
        principalDto.setNextHopPort(nextHopPort);
        return this;
    }

    /**
     * Sets destination network length for creation.
     *
     * @param dstNetworkLength length of the destination network address
     * @return this
     */
    public Route dstNetworkLength(int dstNetworkLength) {
        principalDto.setDstNetworkLength(dstNetworkLength);
        return this;
    }

    /**
     * Sets source network length.
     *
     * @param srcNetworkLength length of the source network address
     * @return this
     */
    public Route srcNetworkLength(int srcNetworkLength) {
        principalDto.setSrcNetworkLength(srcNetworkLength);
        return this;
    }

    /**
     * Sets source network address.
     *
     * @param srcNetworkAddr source network address
     * @return this
     */
    public Route srcNetworkAddr(String srcNetworkAddr) {
        principalDto.setSrcNetworkAddr(srcNetworkAddr);
        return this;
    }

    @Override
    public String toString() {
        return String.format("Route{id=%s}", principalDto.getId());
    }
}
