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
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.packets.MAC;
import org.midonet.util.version.Since;

import static org.midonet.cluster.rest_api.ResourceUris.BRIDGES;
import static org.midonet.cluster.rest_api.ResourceUris.MAC_TABLE;
import static org.midonet.cluster.rest_api.ResourceUris.VLANS;
import static org.midonet.cluster.rest_api.ResourceUris.macToUri;

@XmlRootElement
public class MacPort {

    public static final short UNTAGGED_VLAN_ID = 0;
    @NotNull
    @Pattern(regexp = MAC.regex)
    public String macAddr;

    @NotNull
    public UUID portId;

    @Since("2")
    public Short vlanId;

    // This is only needed for validating that the port belongs to the bridge.
    public UUID bridgeId;

    public URI baseUri;

    public MacPort(URI baseUri, String macAddr, UUID portId) {
        this.baseUri = baseUri;
        this.macAddr = macAddr;
        this.portId = portId;
    }

    /* Default constructor - for deserialization. */
    @SuppressWarnings("unused")
    public MacPort() {
    }

    public URI getUri() {
        UriBuilder builder = UriBuilder.fromUri(baseUri);
        builder.path(BRIDGES).path(bridgeId.toString());
        if (vlanId != null && vlanId != UNTAGGED_VLAN_ID) {
            builder.path(VLANS).path(vlanId.toString());
        }
        return builder.path(MAC_TABLE)
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
