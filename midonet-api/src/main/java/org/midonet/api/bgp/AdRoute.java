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
package org.midonet.api.bgp;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.brain.services.rest_api.ResourceUriBuilder;
import org.midonet.brain.services.rest_api.UriResource;

@XmlRootElement
public class AdRoute extends UriResource {

    private UUID id = null;
    private String nwPrefix = null;
    private byte prefixLength;
    private UUID bgpId = null;

    public AdRoute() {
    }

    public AdRoute(org.midonet.cluster.data.AdRoute data) {
        this(data.getId(), data.getNwPrefix().getHostAddress(),
                data.getPrefixLength(), data.getBgpId());
    }

    public AdRoute(UUID id, String nwPrefix, byte prefixLength, UUID bgpId) {
        this.id = id;
        this.nwPrefix = nwPrefix;
        this.prefixLength = prefixLength;
        this.bgpId = bgpId;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getNwPrefix() {
        return nwPrefix;
    }

    public void setNwPrefix(String nwPrefix) {
        this.nwPrefix = nwPrefix;
    }

    public byte getPrefixLength() {
        return prefixLength;
    }

    public void setPrefixLength(byte prefixLength) {
        this.prefixLength = prefixLength;
    }

    public UUID getBgpId() {
        return bgpId;
    }

    public URI getBgp() {
        if (getBaseUri() != null && bgpId != null) {
            return ResourceUriBuilder.getBgp(getBaseUri(), bgpId);
        } else {
            return null;
        }
    }

    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getAdRoute(getBaseUri(), id);
        } else {
            return null;
        }
    }

    public void setBgpId(UUID bgpId) {
        this.bgpId = bgpId;
    }

    public org.midonet.cluster.data.AdRoute toData () {

        try {
            return new org.midonet.cluster.data.AdRoute()
                    .setId(this.id)
                    .setBgpId(this.bgpId)
                    .setNwPrefix(InetAddress.getByName(this.nwPrefix))
                    .setPrefixLength(this.prefixLength);
        } catch (UnknownHostException e) {
            throw new RuntimeException("Invalid nwPrefix: " + this.nwPrefix, e);
        }
    }

    @Override
    public String toString() {
        return "id=" + id + ", nwPrefix=" + nwPrefix + ", prefixLength="
                + prefixLength + ", bgp=" + bgpId;
    }
}
