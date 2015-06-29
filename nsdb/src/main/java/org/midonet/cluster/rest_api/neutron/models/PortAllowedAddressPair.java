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

package org.midonet.cluster.rest_api.neutron.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.models.Neutron;
import org.midonet.cluster.util.IPAddressUtil;

@ZoomClass(clazz = Neutron.NeutronPort.AllowedAddressPair.class)
public class PortAllowedAddressPair extends ZoomObject {

    @JsonProperty("ip_address")
    @ZoomField(name = "ip_address", converter = IPAddressUtil.Converter.class)
    public String ipAddress;

    @JsonProperty("mac_address")
    @ZoomField(name = "mac_address")
    public String macAddress;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PortAllowedAddressPair)) return false;
        PortAllowedAddressPair that = (PortAllowedAddressPair) o;
        return Objects.equal(ipAddress, that.ipAddress) &&
               Objects.equal(macAddress, that.macAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(ipAddress, macAddress);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
            .add("ipAddress", ipAddress)
            .add("macAddress", macAddress).toString();
    }
}
