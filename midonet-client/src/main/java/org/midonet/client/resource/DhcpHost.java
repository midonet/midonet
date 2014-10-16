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

import org.midonet.client.VendorMediaType;
import org.midonet.client.WebResource;
import org.midonet.client.dto.DtoDhcpHost;

public class DhcpHost extends ResourceBase<DhcpHost, DtoDhcpHost> {

    public DhcpHost(WebResource resource, URI uriForCreation, DtoDhcpHost
        principalDto) {
        super(resource, uriForCreation, principalDto,
                VendorMediaType.APPLICATION_DHCP_HOST_JSON);
    }

    /**
     * Gets URI for this subnet host.
     * @return
     */
    @Override
    public URI getUri() {
        return principalDto.getUri();
    }

    /**
     * Gets name of this host.
     * @return
     */
    public String getName() {
        return principalDto.getName();
    }

    /**
     * Gets Ip address of the host.
     * @return
     */
    public String getIpAddr() {
        return principalDto.getIpAddr();
    }

    /**
     *  Gets mac address of the host.
     * @return
     */
    public String getMacAddr() {
        return principalDto.getMacAddr();
    }

    /**
     * Sets name of the host.
     * @param name
     * @return               this
     */
    public DhcpHost name(String name) {
        principalDto.setName(name);
        return this;
    }

    /**
     * Sets mac address of the host.
     * @param macAddr
     * @return                      this
     */
    public DhcpHost macAddr(String macAddr) {
        principalDto.setMacAddr(macAddr);
        return this;
    }

    /**
     * Sets ip address of the host.
     * @param ipAddr
     * @return
     */
    public DhcpHost ipAddr(String ipAddr) {
        principalDto.setIpAddr(ipAddr);
        return this;
    }

    @Override
    public String toString() {
        return String.format("{DhcpHost, ip=%s, mac=%s}", principalDto
                .getIpAddr(), principalDto.getMacAddr());
    }
}


