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

package org.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

@XmlRootElement
public class DtoDhcpHost {
    protected String macAddr;
    protected String ipAddr;    // DHCP "your ip address"
    protected String name;      // DHCP option 12 - host name
    protected List<DtoExtraDhcpOpt> extraDhcpOpts;
    private URI uri;

    public String getMacAddr() {
        return macAddr;
    }

    public void setMacAddr(String macAddr) {
        this.macAddr = macAddr;
    }

    public String getIpAddr() {
        return ipAddr;
    }

    public void setIpAddr(String ipAddr) {
        this.ipAddr = ipAddr;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<DtoExtraDhcpOpt> getExtraDhcpOpts() {
        return extraDhcpOpts;
    }

    public void setExtraDhcpOpts(List<DtoExtraDhcpOpt> extraDhcpOpts) {
        this.extraDhcpOpts = extraDhcpOpts;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(macAddr, ipAddr, name, extraDhcpOpts, uri);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof DtoDhcpHost)) return false;
        final DtoDhcpHost other = (DtoDhcpHost) obj;
        return Objects.equal(macAddr, other.macAddr) &&
               Objects.equal(ipAddr, other.ipAddr) &&
               Objects.equal(name, other.name) &&
               Objects.equal(extraDhcpOpts, other.extraDhcpOpts) &&
               Objects.equal(uri, other.uri);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("macAddr", macAddr)
            .add("ipAddr", ipAddr)
            .add("name", name)
            .add("extraDhcpOpts", extraDhcpOpts)
            .add("uri", uri)
            .toString();
    }
}
