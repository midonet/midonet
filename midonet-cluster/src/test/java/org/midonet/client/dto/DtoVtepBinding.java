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

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

public class DtoVtepBinding {
    private String portName;
    private short vlanId;
    private UUID networkId;
    private UUID vtepId;

    private URI uri;

    public String getPortName() {
        return portName;
    }

    public void setPortName(String portName) {
        this.portName = portName;
    }

    public short getVlanId() {
        return vlanId;
    }

    public void setVlanId(short vlanId) {
        this.vlanId = vlanId;
    }

    public UUID getVtepId() {
        return vtepId;
    }

    public void setVtepId(UUID vtepId) {
        this.vtepId = vtepId;
    }

    public UUID getNetworkId() {
        return networkId;
    }

    public void setNetworkId(UUID networkId) {
        this.networkId = networkId;
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

        DtoVtepBinding that = (DtoVtepBinding) o;

        return vlanId == that.vlanId &&
               Objects.equals(vtepId, that.vtepId) &&
               Objects.equals(portName, that.portName) &&
               Objects.equals(networkId, that.networkId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vtepId, portName, vlanId, networkId);
    }

    @Override
    public String toString() {
        return "DtoVtepBinding{" +
            "portName='" + portName + '\'' +
            ", vlanId=" + vlanId +
            ", vtepId=" + vtepId +
            ", networkId=" + networkId +
            ", uri=" + uri +
            '}';
    }
}
