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

import java.net.URI;
import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.cluster.rest_api.ResourceUris;
import org.midonet.packets.IPv4;
import org.midonet.packets.MAC;

public class Ip4MacPair extends UriResource {

    @NotNull
    @Pattern(regexp = IPv4.regex)
    public String ip;

    @NotNull
    @Pattern(regexp = MAC.regex)
    public String mac;

    @JsonIgnore
    public UUID bridgeId;

    /* Default constructor - for deserialization. */
    @SuppressWarnings("unused")
    public Ip4MacPair() {
    }

    public Ip4MacPair(URI baseUri, UUID bridgeId, String ip, String mac) {
        setBaseUri(baseUri);
        this.bridgeId = bridgeId;
        this.ip = ip;
        this.mac = mac;
    }

    @Override
    public URI getUri() {
        return absoluteUri(ResourceUris.BRIDGES(), bridgeId,
                           ResourceUris.ARP_TABLE(),
                           ip + "_" + ResourceUris.macToUri(mac));
    }
}
