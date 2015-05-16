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
package org.midonet.cluster.rest_api.models;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class Bgp extends UriResource {

    public UUID id = null;
    public int localAS;
    public String peerAddr = null;
    public int peerAS;
    public UUID portId = null;
    public String status;

    public Bgp() {}

    public Bgp(UUID id, int localAS, String peerAddr, int peerAS, UUID portId, String status) {
        this.id = id;
        this.localAS = localAS;
        this.peerAddr = peerAddr;
        this.peerAS = peerAS;
        this.portId = portId;
        this.status = status;
    }

    public Bgp(UUID id, int localAS, String peerAddr, int peerAS, UUID portId) {
        this(id, localAS, peerAddr, peerAS, portId, "");
    }

    public URI getPort() {
        return absoluteUri(ResourceUris.PORTS, portId);
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.BGP, id);
    }

    public URI getAdRoutes() {
        return relativeUri(ResourceUris.AD_ROUTES);
    }

}

