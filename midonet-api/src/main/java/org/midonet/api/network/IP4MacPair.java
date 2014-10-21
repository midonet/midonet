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

package org.midonet.api.network;

import java.net.URI;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.api.RelativeUriResource;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.packets.IPv4;
import org.midonet.packets.MAC;

@XmlRootElement
public class IP4MacPair extends RelativeUriResource {

    @NotNull
    @Pattern(regexp = IPv4.regex)
    private String ip;

    @NotNull
    @Pattern(regexp = MAC.regex)
    protected String macAddr;

    /* Default constructor - for deserialization. */
    public IP4MacPair() {}

    public IP4MacPair(String ip, String macAddr) {
        this.ip = ip;
        this.macAddr = macAddr;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getMac() {
        return macAddr;
    }

    public void setMac(String macAddr) {
        this.macAddr = macAddr;
    }

    @Override
    public URI getUri() {
        if (getParentUri() != null && macAddr != null && ip != null) {
            return ResourceUriBuilder.getIP4MacPair(getParentUri(), this);
        } else {
            return null;
        }
    }
}
