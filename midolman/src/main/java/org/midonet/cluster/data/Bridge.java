/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Bridge extends Entity.Base<UUID, Bridge.Data, Bridge>
        implements Entity.TaggableEntity{

    // As a convention, we consider frames with no VLAN tags
    // as VLAN ID 0
    public static final short UNTAGGED_VLAN_ID = 0;

    public enum Property {
        tenant_id
    }

    public Bridge() {
        this(null, new Data());
    }

    public Bridge(UUID id){
        this(id, new Data());
    }

    public Bridge(Data data){
        this(null, data);
    }

    public Bridge(UUID uuid, Data data) {
        super(uuid, data);
    }

    @Override
    protected Bridge self() {
        return this;
    }

    public String getName() {
        return getData().name;
    }

    public Bridge setName(String name) {
        getData().name = name;
        return this;
    }

    public UUID getInboundFilter() {
        return getData().inboundFilter;
    }

    public Bridge setInboundFilter(UUID filter) {
        getData().inboundFilter = filter;
        return this;
    }

    public UUID getOutboundFilter() {
        return getData().outboundFilter;
    }

    public Bridge setOutboundFilter(UUID filter) {
        getData().outboundFilter = filter;
        return this;
    }

    public int getTunnelKey() {
        return getData().tunnelKey;
    }

    public Bridge setTunnelKey(int tunnelKey) {
        getData().tunnelKey = tunnelKey;
        return this;
    }

    public Bridge setProperty(Property property, String value) {
        getData().properties.put(property.name(), value);
        return this;
    }

    public Bridge setProperties(Map<String, String> properties) {
        getData().properties = properties;
        return this;
    }

    public String getProperty(Property property) {
        return getData().properties.get(property.name());
    }

    public Map<String, String> getProperties() {
        return getData().properties;
    }

    public static class Data {
        public String name;
        public int tunnelKey;
        public UUID inboundFilter;
        public UUID outboundFilter;
        public Map<String, String> properties = new HashMap<String, String>();

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            Data that = (Data) o;

            if (tunnelKey != that.tunnelKey)
                return false;
            if (inboundFilter != null ? !inboundFilter
                .equals(that.inboundFilter) : that.inboundFilter != null)
                return false;
            if (outboundFilter != null ? !outboundFilter
                .equals(that.outboundFilter) : that.outboundFilter != null)
                return false;
            if (name != null ? !name.equals(that.name) : that.name != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = tunnelKey;
            result = 31 * result
                + (inboundFilter != null ? inboundFilter.hashCode() : 0);
            result = 31 * result
                + (outboundFilter != null ? outboundFilter.hashCode() : 0);
            result = 31 * result
                + (name != null ? name.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "Bridge.Data{" + "tunnelKey=" + tunnelKey + ", inboundFilter="
                + inboundFilter + ", outboundFilter=" + outboundFilter
                + ", name=" + name + '}';
        }
    }
}
