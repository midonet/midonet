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
package org.midonet.cluster.data.boilerplate;

import java.util.Objects;
import java.util.UUID;

public class VtepBinding {

    private final String portName;
    private final short vlanId;
    private final UUID networkId;

    public VtepBinding(String portName, short vlanId, UUID networkId) {
        this.portName = portName;
        this.vlanId = vlanId;
        this.networkId = networkId;
    }

    public String getPortName() {
        return portName;
    }

    public short getVlanId() {
        return vlanId;
    }

    public UUID getNetworkId() {
        return networkId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        VtepBinding that = (VtepBinding) o;

        return vlanId == that.vlanId &&
                Objects.equals(networkId, that.networkId) &&
                Objects.equals(portName, that.portName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(vlanId, networkId, portName);
    }

    @Override
    public String toString() {
        return "VtepBinding{" +
                "portName='" + portName + '\'' +
                ", vlanId=" + vlanId +
                ", networkId=" + networkId +
                '}';
    }
}
