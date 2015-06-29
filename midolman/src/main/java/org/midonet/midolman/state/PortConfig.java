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

package org.midonet.midolman.state;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import org.midonet.midolman.state.PortDirectory.BridgePortConfig;
import org.midonet.midolman.state.PortDirectory.ExteriorBridgePortConfig;
import org.midonet.midolman.state.PortDirectory.ExteriorRouterPortConfig;
import org.midonet.midolman.state.PortDirectory.InteriorBridgePortConfig;
import org.midonet.midolman.state.PortDirectory.InteriorRouterPortConfig;
import org.midonet.midolman.state.PortDirectory.RouterPortConfig;
import org.midonet.midolman.state.PortDirectory.VxLanPortConfig;
import org.midonet.midolman.state.zkManagers.ConfigWithProperties;
import org.midonet.packets.IPv4Subnet;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
    property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = BridgePortConfig.class,
        name = "BridgePort"),
    @JsonSubTypes.Type(value = RouterPortConfig.class,
        name = "RouterPort"),
    @JsonSubTypes.Type(value = ExteriorBridgePortConfig.class,
        name = "ExteriorBridgePort"),
    @JsonSubTypes.Type(value = ExteriorRouterPortConfig.class,
        name = "ExteriorRouterPort"),
    @JsonSubTypes.Type(value = InteriorBridgePortConfig.class,
        name = "InteriorBridgePort"),
    @JsonSubTypes.Type(value = InteriorRouterPortConfig.class,
        name = "InteriorRouterPort"),
    @JsonSubTypes.Type(value = VxLanPortConfig.class,
        name = "VxLanPort")})
public abstract class PortConfig extends ConfigWithProperties {

    PortConfig(UUID device_id) {
        this(device_id, false);
    }

    PortConfig(UUID device_id, boolean adminStateUp) {
        super();
        this.device_id = device_id;
        this.adminStateUp = adminStateUp;
    }

    // Default constructor for the Jackson deserialization.
    PortConfig() { super(); }
    public UUID device_id;
    public boolean adminStateUp;
    public UUID inboundFilter;
    public UUID outboundFilter;
    public Set<UUID> portGroupIDs;
    public int tunnelKey;

    public UUID hostId;
    public String interfaceName;
    public UUID peerId;
    public String v1ApiType;

    public String getV1ApiType() { return v1ApiType; }
    public void setV1ApiType(String type) { v1ApiType=type; }

    // Custom accessors for Jackson serialization
    public UUID getHostId() { return hostId; }
    public void setHostId(UUID hostId) {
        this.hostId = hostId;
    }
    public String getInterfaceName() { return interfaceName; }
    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
    }
    public UUID getPeerId() { return peerId; }
    public void setPeerId(UUID pId) { peerId = pId; }

    @JsonIgnore
    public boolean isInterior() { return peerId != null; }
    @JsonIgnore
    public boolean isExterior() { return hostId != null; }
    @JsonIgnore
    public boolean isUnplugged() { return !isInterior() && !isExterior(); }
    @JsonIgnore
    public boolean hasSubnet(IPv4Subnet sub) { return false; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        PortConfig that = (PortConfig) o;

        if (tunnelKey != that.tunnelKey) return false;
        if (device_id != null
                ? !device_id.equals(that.device_id) : that.device_id != null)
            return false;
        if (inboundFilter != null
                ? !inboundFilter.equals(that.inboundFilter)
                : that.inboundFilter != null)
            return false;
        if (outboundFilter != null
                ? !outboundFilter.equals(that.outboundFilter)
                : that.outboundFilter != null)
            return false;
        if (portGroupIDs != null ? !portGroupIDs.equals(that.portGroupIDs)
                : that.portGroupIDs != null)
            return false;
        if (v1ApiType != null ? v1ApiType.equals(that.v1ApiType)
                : that.v1ApiType != null)
            return false;
        return
            hostId == null ? that.hostId == null : hostId.equals(that.hostId)
            && interfaceName == null ?
               that.interfaceName == null :
               interfaceName.equals(that.interfaceName)
            && peerId == null? that.peerId == null :
               peerId.equals(that.peerId)
            && adminStateUp == that.adminStateUp;
    }

    @Override
    public int hashCode() {
        int result = device_id != null ? device_id.hashCode() : 0;
        result = 31 * result +
                (inboundFilter != null ? inboundFilter.hashCode() : 0);
        result = 31 * result +
                (outboundFilter != null ? outboundFilter.hashCode() : 0);
        result = 31 * result +
                (portGroupIDs != null ? portGroupIDs.hashCode() : 0);
        result = 31 * result + tunnelKey;
        result = 31 * result + (peerId != null ? peerId.hashCode() : 0);
        result = 31 * result + (hostId != null ? hostId.hashCode() : 0);
        result = 31 * result +
                 (interfaceName != null ? interfaceName.hashCode() : 0);
        result = 31 * result +
            (v1ApiType != null ? v1ApiType.hashCode() : 0);
        result = 31 * result + Boolean.valueOf(adminStateUp).hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "PortConfig{ device_id=," + device_id +
                ", hostId=" + hostId +
                ", interfaceName=" + interfaceName +
                ", peerId=" + peerId +
                ", adminStateUp=" + adminStateUp + '}';
    }
}
