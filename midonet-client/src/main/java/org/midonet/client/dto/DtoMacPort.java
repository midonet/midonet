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
import java.util.UUID;

@XmlRootElement
public class DtoMacPort {
    String macAddr;
    UUID portId;
    Short vlanId;
    URI uri;

    // Default constructor needed for deserialization
    public DtoMacPort() {}

    public DtoMacPort(String macAddr, UUID portId) {
        this.macAddr = macAddr;
        this.portId = portId;
    }

    public DtoMacPort(String macAddr, UUID portId, Short vlanId) {
        this(macAddr, portId);
        this.vlanId = vlanId;
    }

    public String getMacAddr() {
        return macAddr;
    }

    public void setMacAddr(String macAddr) {
        this.macAddr = macAddr;
    }

    public UUID getPortId() {
        return portId;
    }

    public void setPortId(UUID portId) {
        this.portId = portId;
    }

    public Short getVlanId() {
        return vlanId;
    }

    public void setVlanId(Short vlanId) {
        this.vlanId = vlanId;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoMacPort that = (DtoMacPort) o;

        if (macAddr != null ? !macAddr.equals(that.macAddr) : that.macAddr != null)
            return false;
        if (portId != null ? !portId.equals(that.portId) : that.portId != null)
            return false;
        if (vlanId != null ? !vlanId.equals(that.vlanId) : that.vlanId != null)
            return false;
        if (uri != null ? !uri.equals(that.uri) : that.uri != null)
            return false;

        return true;
    }

    @Override
    public String toString() {
        return "DtoMacPort{" +
               "macAddr='" + macAddr + '\'' +
               ", portId=" + portId +
               ", vlanId=" + vlanId +
               ", uri=" + uri +
               '}';
    }

    @Override
    public int hashCode() {
        int result = macAddr != null ? macAddr.hashCode() : 0;
        result = 31 * result + (portId != null ? portId.hashCode() : 0);
        result = 31 * result + (vlanId != null ? vlanId.hashCode() : 0);
        result = 31 * result + (uri != null ? uri.hashCode() : 0);
        return result;
    }
}
