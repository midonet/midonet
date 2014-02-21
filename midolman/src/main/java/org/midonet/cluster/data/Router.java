/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;

public class Router extends Entity.Base<UUID, Router.Data, Router>  {

    public enum Property {
        tenant_id
    }

    public Router() {
        this(null, new Data());
    }

    public Router(UUID uuid) {
        super(uuid, new Data());
    }

    public Router(UUID uuid, @Nonnull Data data) {
        super(uuid, data);
    }

    @Override
    protected Router self() {
        return this;
    }

    public Router setName(String name) {
        getData().name = name;
        return self();
    }

    public String getName() {
        return getData().name;
    }

    public boolean isAdminStateUp() {
        return getData().adminStateUp;
    }

    public Router setAdminStateUp(boolean adminStateUp) {
        this.getData().adminStateUp = adminStateUp;
        return this;
    }

    public Router setInboundFilter(UUID inboundFilter) {
        getData().inboundFilter = inboundFilter;
        return self();
    }

    public UUID getInboundFilter() {
        return getData().inboundFilter;
    }

    public Router setOutboundFilter(UUID outboundFilter) {
        getData().outboundFilter = outboundFilter;
        return self();
    }

    public UUID getOutboundFilter() {
        return getData().outboundFilter;
    }

    public Router setLoadBalancer(UUID loadBalancer) {
        getData().loadBalancer = loadBalancer;
        return self();
    }

    public UUID getLoadBalancer() {
        return getData().loadBalancer;
    }

    public Router setProperty(Property key, String value) {
        getData().properties.put(key.name(), value);
        return self();
    }

    public Router setProperty(String key, String value) {
        getData().properties.put(key, value);
        return self();
    }

    public Router setProperties(Map<String, String> properties) {
        getData().properties.putAll(properties);
        return self();
    }

    public String getProperty(Property property) {
        return getData().properties.get(property.name());
    }

    public Map<String, String> getProperties() {
        return getData().properties;
    }

    public static class Data {

        public String name;
        public UUID inboundFilter;
        public UUID outboundFilter;
        public UUID loadBalancer;
        public Map<String, String> properties = new HashMap<String, String>();
        public boolean adminStateUp = true;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            if (inboundFilter != null ? !inboundFilter.equals(
                data.inboundFilter) : data.inboundFilter != null) return false;
            if (name != null ? !name.equals(data.name) : data.name != null)
                return false;
            if (outboundFilter != null ? !outboundFilter.equals(
                data.outboundFilter) : data.outboundFilter != null)
                return false;
            if (loadBalancer != null ? !loadBalancer.equals(
                    data.loadBalancer) : data.loadBalancer != null)
                return false;
            if (properties != null ? !properties.equals(
                data.properties) : data.properties != null) return false;
            if (adminStateUp != data.adminStateUp) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = name != null ? name.hashCode() : 0;
            result = 31 * result +
                    (inboundFilter != null ? inboundFilter.hashCode() : 0);
            result = 31 * result +
                    (outboundFilter != null ? outboundFilter.hashCode() : 0);
            result = 31 * result +
                    (loadBalancer != null ? loadBalancer.hashCode() : 0);
            result = 31 * result +
                    (properties != null ? properties.hashCode() : 0);
            result = 31 * result + Boolean.valueOf(adminStateUp).hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "Router.Data{" +
                    "name='" + name + '\'' +
                    ", inboundFilter=" + inboundFilter +
                    ", outboundFilter=" + outboundFilter +
                    ", loadBalancer=" + loadBalancer +
                    ", properties=" + properties +
                    ", adminStateUp=" + adminStateUp +
                    '}';
        }
    }
}
