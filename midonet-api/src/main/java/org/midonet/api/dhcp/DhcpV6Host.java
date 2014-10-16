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

package org.midonet.api.dhcp;

import org.midonet.api.RelativeUriResource;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.cluster.data.dhcp.V6Host;
import org.midonet.packets.IPv6Addr;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;

@XmlRootElement
public class DhcpV6Host extends RelativeUriResource {
    protected String clientId;
    protected String fixedAddress;
    protected String name;

    public DhcpV6Host(String clientId, String fixedAddress, String name) {
        this.clientId = clientId;
        this.fixedAddress = fixedAddress;
        this.name = name;
    }

    /* Default constructor - for deserialization. */
    public DhcpV6Host() {
    }

    public DhcpV6Host(V6Host host) {
        this.fixedAddress = host.getFixedAddress().toString();
        this.clientId = host.getClientId().toString();
        this.name = host.getName();
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getParentUri() != null && clientId != null) {
            return ResourceUriBuilder.getDhcpV6Host(getParentUri(), clientId);
        } else {
            return null;
        }
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getFixedAddress() {
        return fixedAddress;
    }

    public void setFixedAddress(String fixedAddress) {
        this.fixedAddress = fixedAddress;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public V6Host toData() {
        return new V6Host()
                .setFixedAddress(IPv6Addr.fromString(this.fixedAddress))
                .setClientId(this.clientId)
                .setName(this.name);
    }

}
