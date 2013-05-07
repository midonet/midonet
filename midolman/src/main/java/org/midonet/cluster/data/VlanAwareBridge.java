/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class VlanAwareBridge
    extends Entity.Base<UUID, VlanAwareBridge.Data, VlanAwareBridge> {

    public enum Property {
        tenant_id
    }

    public VlanAwareBridge() {
        this(null, new Data());
    }

    public VlanAwareBridge(UUID id){
        this(id, new Data());
    }

    public VlanAwareBridge(Data data){
        this(null, data);
    }

    public VlanAwareBridge(UUID uuid, Data data) {
        super(uuid, data);
    }

    @Override
    protected VlanAwareBridge self() {
        return this;
    }

    public String getName() {
        return getData().name;
    }

    public VlanAwareBridge setName(String name) {
        getData().name = name;
        return this;
    }

    public int getTunnelKey() {
        return getData().tunnelKey;
    }

    public VlanAwareBridge setTunnelKey(int tunnelKey) {
        getData().tunnelKey = tunnelKey;
        return this;
    }

    public VlanAwareBridge setProperty(Property property, String value) {
        getData().properties.put(property.name(), value);
        return this;
    }

    public VlanAwareBridge setProperties(Map<String, String> properties) {
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
        public Map<String, String> properties = new HashMap<String, String>();

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data that = (Data) o;

            if (tunnelKey != that.tunnelKey) return false;
            if (name != null ? !name.equals(that.name) : that.name != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = tunnelKey;
            result = 31 * result + (name != null ? name.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "VlanAwareBridge.Data{" + "tunnelKey=" + tunnelKey +
                ", name=" + name + "}";
        }
    }
}
