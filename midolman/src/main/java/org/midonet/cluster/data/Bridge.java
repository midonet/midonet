/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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

    public boolean isAdminStateUp() {
        return getData().adminStateUp;
    }

    public Bridge setAdminStateUp(boolean adminStateUp) {
        getData().adminStateUp = adminStateUp;
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

    public UUID getVxLanPortId() { return getData().vxLanPortId; }

    public Bridge setVxLanPortId(UUID vxlanPortId) {
        getData().vxLanPortId = vxlanPortId;
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
        public boolean adminStateUp = true;
        public int tunnelKey;
        public UUID inboundFilter;
        public UUID outboundFilter;
        public UUID vxLanPortId;
        public Map<String, String> properties = new HashMap<String, String>();

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            Data that = (Data) o;

            return tunnelKey == that.tunnelKey &&
                    adminStateUp == that.adminStateUp &&
                    Objects.equals(inboundFilter, that.inboundFilter) &&
                    Objects.equals(outboundFilter, that.outboundFilter) &&
                    Objects.equals(vxLanPortId, that.vxLanPortId) &&
                    Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tunnelKey, adminStateUp, inboundFilter,
                                outboundFilter, vxLanPortId, name);
        }

        @Override
        public String toString() {
            return "Bridge.Data{" + "tunnelKey=" + tunnelKey +
                   ", inboundFilter=" + inboundFilter +
                   ", outboundFilter=" + outboundFilter +
                   ", vxLanPortId=" + vxLanPortId +
                   ", name=" + name +
                    ", adminStateUp=" + adminStateUp + '}';
        }
    }
}
