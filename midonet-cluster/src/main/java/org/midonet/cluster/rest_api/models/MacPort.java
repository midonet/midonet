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
import java.util.Objects;
import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.ws.rs.core.UriBuilder;

import com.fasterxml.jackson.annotation.JsonIgnore;

import org.midonet.packets.MAC;

import static org.midonet.cluster.rest_api.ResourceUris.BRIDGES;
import static org.midonet.cluster.rest_api.ResourceUris.MAC_TABLE;
import static org.midonet.cluster.rest_api.ResourceUris.VLANS;
import static org.midonet.cluster.rest_api.ResourceUris.macToUri;

public class MacPort extends UriResource {

    public static final short UNTAGGED_VLAN_ID = 0;

    @NotNull
    @Pattern(regexp = MAC.regex)
    public String macAddr;

    @NotNull
    public UUID portId;

    public Short vlanId;

    @JsonIgnore
    public UUID bridgeId;

    /* Default constructor - for deserialization. */
    @SuppressWarnings("unused")
    public MacPort() {
    }

    public MacPort(URI baseUri, UUID bridgeId, String macAddr, UUID portId,
                   Short vlanId) {
        this(baseUri, bridgeId, macAddr, portId);
        this.vlanId = vlanId;
    }

    public MacPort(URI baseUri, UUID bridgeId, String macAddr, UUID portId) {
        setBaseUri(baseUri);
        this.bridgeId = bridgeId;
        this.macAddr = macAddr;
        this.portId = portId;
    }

    @Override
    public URI getUri() {
        UriBuilder builder = UriBuilder.fromUri(getBaseUri());
        builder.path(BRIDGES()).path(bridgeId.toString());
        if (vlanId != null && vlanId != UNTAGGED_VLAN_ID) {
            builder.path(VLANS()).path(vlanId.toString());
        }
        return builder.path(MAC_TABLE())
                      .path(macToUri(macAddr) + "_" + portId.toString())
                      .build();
    }

    @Override
    public String toString() {
        return "MacPort{bridgeId=" + bridgeId + ", mac=" + macAddr + ", port="
               + portId + ", vlanId=" + vlanId + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        MacPort that = (MacPort) o;
        return Objects.equals(macAddr, that.macAddr) &&
               Objects.equals(portId, that.portId) &&
               Objects.equals(vlanId, that.vlanId) &&
               Objects.equals(bridgeId, that.bridgeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(macAddr, portId, vlanId, bridgeId);
    }
}
