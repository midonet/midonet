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
package org.midonet.cluster.data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Recursive type definition enables use of a builder pattern,
 * such as where set__() methods have return type Self.
 */
public abstract class Port<PortData extends Port.Data,
                           Self extends Port<PortData, Self>>
    extends Entity.Base<UUID, PortData, Self> {

    public enum Property {
        vif_id, peer_id, v1PortType
    }

    protected Port(UUID uuid, PortData portData) {
        super(uuid, portData);
    }

    protected Port(PortData portData) {
        super(null, portData);
    }

    public Self setDeviceId(UUID deviceId) {
        getData().device_id = deviceId;
        return self();
    }

    public UUID getDeviceId() {
        return getData().device_id;
    }

    public Self setAdminStateUp(boolean adminStateUp) {
        getData().adminStateUp = adminStateUp;
        return self();
    }

    public boolean isAdminStateUp() {
        return getData().adminStateUp;
    }

    public Self setInboundFilter(UUID filterId) {
        getData().inboundFilter = filterId;
        return self();
    }

    public UUID getInboundFilter() {
        return getData().inboundFilter;
    }

    public Self setOutboundFilter(UUID filterId) {
        getData().outboundFilter = filterId;
        return self();
    }

    public UUID getOutboundFilter() {
        return getData().outboundFilter;
    }

    public Self setPortGroups(Set<UUID> portGroups) {
        getData().portGroupIDs.clear();
        if (portGroups != null) {
            getData().portGroupIDs.addAll(portGroups);
        }

        return self();
    }

    public Set<UUID> getPortGroups() {
        return getData().portGroupIDs;
    }

    public Self setTunnelKey(int tunnelKey) {
        getData().tunnelKey = tunnelKey;
        return self();
    }

    public int getTunnelKey() {
        return getData().tunnelKey;
    }

    public Self setProperties(Map<String, String> properties) {
        getData().properties.clear();
        if (properties != null) {
            getData().properties.putAll(properties);
        }

        return self();
    }

    public Self setProperty(Property key, String value) {
        getData().properties.put(key.name(), value);
        return self();
    }

    public Self setProperty(String key, String value) {
        getData().properties.put(key, value);
        return self();
    }

    public Map<String, String> getProperties() {
        return getData().properties;
    }

    public String getProperty(Property property) {
        return getData().properties.get(property.name());
    }

    public Self setPeerId(UUID peerId) {
        getData().peer_uuid = peerId;
        return self();
    }

    public UUID getHostId() {
        return getData().hostId;
    }

    public Self setHostId(UUID hostId) {
        getData().hostId = hostId;
        return self();
    }

    public String getInterfaceName() {
        return getData().interfaceName;
    }

    public Self setInterfaceName(String interfaceName) {
        getData().interfaceName = interfaceName;
        return self();
    }

    public UUID getPeerId() {
        return getData().peer_uuid;
    }

    public boolean isInterior() {
        return getPeerId() != null;
    }

    public boolean isExterior() {
        return getHostId() != null && getInterfaceName() != null;
    }

    public boolean isUnplugged() {
        return !isInterior() && !isExterior();
    }

    public static class Data {
        public UUID device_id;
        public UUID inboundFilter;
        public UUID outboundFilter;
        public Set<UUID> portGroupIDs = new HashSet<UUID>();
        public int tunnelKey;
        public Map<String, String> properties = new HashMap<String, String>();
        public UUID peer_uuid;
        public UUID hostId;
        public String interfaceName;
        public boolean adminStateUp = true;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            if (tunnelKey != data.tunnelKey) return false;
            if (device_id != null ? !device_id.equals(
                data.device_id) : data.device_id != null) return false;
            if (inboundFilter != null ? !inboundFilter.equals(
                data.inboundFilter) : data.inboundFilter != null) return false;
            if (outboundFilter != null ? !outboundFilter.equals(
                data.outboundFilter) : data.outboundFilter != null)
                return false;
            if (portGroupIDs != null ? !portGroupIDs.equals(
                data.portGroupIDs) : data.portGroupIDs != null) return false;
            if (properties != null ? !properties.equals(
                data.properties) : data.properties != null) return false;
            if (peer_uuid != null ? !peer_uuid.equals(
                    data.peer_uuid) : data.peer_uuid != null) return false;
            if (hostId != null ? !hostId.equals(
                    data.hostId) : data.hostId != null)
                return false;
            if (interfaceName != null ? !interfaceName.equals(
                    data.interfaceName) : data.interfaceName != null)
                return false;
            if (adminStateUp != data.adminStateUp) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = device_id != null ? device_id.hashCode() : 0;
            result = 31 * result + (inboundFilter != null ? inboundFilter.hashCode() : 0);
            result = 31 * result + (outboundFilter != null ? outboundFilter.hashCode() : 0);
            result = 31 * result + (portGroupIDs != null ? portGroupIDs.hashCode() : 0);
            result = 31 * result + tunnelKey;
            result = 31 * result + (properties != null ? properties.hashCode() : 0);
            result = 31 * result + (peer_uuid != null ? peer_uuid.hashCode() : 0);
            result = 31 * result + (hostId != null ? hostId.hashCode() : 0);
            result = 31 * result + (interfaceName != null ? interfaceName.hashCode() : 0);
            result = 31 * result + Boolean.valueOf(adminStateUp).hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "Data{" +
                    "device_id=" + device_id +
                    ", inboundFilter=" + inboundFilter +
                    ", outboundFilter=" + outboundFilter +
                    ", portGroupIDs=" + portGroupIDs +
                    ", tunnelKey=" + tunnelKey +
                    ", properties=" + properties +
                    ", peer_uuid=" + peer_uuid +
                    ", hostId=" + hostId +
                    ", interfaceName='" + interfaceName + '\'' +
                    ", adminStateUp=" + adminStateUp +
                    '}';
        }
    }
}
