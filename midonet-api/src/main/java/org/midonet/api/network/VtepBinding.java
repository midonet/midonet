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
import java.util.UUID;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.UriResource;

public class VtepBinding extends UriResource {

    // Not included in DTO. Used only to generate URI.
    private String mgmtIp;

    @NotNull
    private String portName;

    @Min(0)
    @Max(4095)
    private short vlanId;

    @NotNull
    private UUID networkId;

    public VtepBinding() {}

    public VtepBinding(String mgmtIp, String portName,
                       short vlanId, UUID networkId) {
        setMgmtIp(mgmtIp);
        setPortName(portName);
        setVlanId(vlanId);
        setNetworkId(networkId);
    }

    public String getMgmtIp() {
        return mgmtIp;
    }

    public void setMgmtIp(String mgmtIp) {
        this.mgmtIp = mgmtIp;
    }

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

    public UUID getNetworkId() {
        return networkId;
    }

    public void setNetworkId(UUID networkId) {
        this.networkId = networkId;
    }

    public URI getUri() {
        return (getBaseUri() == null || mgmtIp == null) ? null :
                ResourceUriBuilder.getVtepBinding(
                        getBaseUri(), mgmtIp, portName, vlanId);
    }

    @Override
    public String toString() {
        return "VtepBinding{" +
            "mgmtIp='" + mgmtIp + '\'' +
            ", portName='" + portName + '\'' +
            ", vlanId=" + vlanId +
            ", networkId=" + networkId +
            '}';
    }
}
