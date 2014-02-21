/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.cluster.data;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AdRoute extends Entity.Base<UUID, AdRoute.Data, AdRoute> {

    public enum Property {
    }

    public AdRoute() {
        this(null, new Data());
    }

    public AdRoute(UUID uuid, Data data) {
        super(uuid, data);
    }

    @Override
    protected AdRoute self() {
        return this;
    }

    public InetAddress getNwPrefix() {
        return getData().nwPrefix;
    }

    public AdRoute setNwPrefix(InetAddress nwPrefix) {
        getData().nwPrefix = nwPrefix;
        return this;
    }

    public byte getPrefixLength() {
        return getData().prefixLength;
    }

    public AdRoute setPrefixLength(byte prefixLength) {
        getData().prefixLength = prefixLength;
        return this;
    }

    public UUID getBgpId() {
        return getData().bgpId;
    }

    public AdRoute setBgpId(UUID bgpId) {
        getData().bgpId = bgpId;
        return this;
    }

    public AdRoute setProperty(Property property, String value) {
        getData().properties.put(property.name(), value);
        return this;
    }

    public AdRoute setProperties(Map<String, String> properties) {
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

        public InetAddress nwPrefix;
        public byte prefixLength;
        public UUID bgpId;
        public Map<String, String> properties = new HashMap<String, String>();

        @Override
        public String toString() {
            return "Data{" +
                    "nwPrefix=" + nwPrefix +
                    ", prefixLength=" + prefixLength +
                    ", bgpId=" + bgpId +
                    ", properties=" + properties +
                    '}';
        }
    }
}
