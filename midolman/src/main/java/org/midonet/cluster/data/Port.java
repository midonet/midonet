/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public abstract class Port<PortData extends Port.Data,
                           Self extends Port<PortData, Self>>
    extends Entity.Base<UUID, PortData, Self> {

    public enum Property {
        vif_id
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

    public static class Data {
        public UUID device_id;
        public UUID inboundFilter;
        public UUID outboundFilter;
        public Set<UUID> portGroupIDs = new HashSet<UUID>();
        public int tunnelKey;
        public Map<String, String> properties = new HashMap<String, String>();


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
            return result;
        }
    }
}
