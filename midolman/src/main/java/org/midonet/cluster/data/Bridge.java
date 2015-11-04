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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class Bridge extends Entity.Base<UUID, Bridge.Data, Bridge>
        implements Entity.TaggableEntity{

    // As a convention, we consider frames with no VLAN tags as VLAN ID 0
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

    public boolean getDisableAntiSpoof() {
        return getData().disableAntiSpoof;
    }

    public Bridge setDisableAntiSpoof(boolean disableAntiSpoof) {
        getData().disableAntiSpoof = disableAntiSpoof;
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

    public List<UUID> getVxLanPortIds() { return getData().vxLanPortIds; }

    public Bridge setVxLanPortIds(List<UUID> vxlanPortIds) {
        getData().vxLanPortIds = vxlanPortIds;
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

    public boolean hasTenantId(String tenantId) {
        return Objects.equals(getProperty(Property.tenant_id), tenantId);
    }

    public static class Data {
        public String name;
        public boolean adminStateUp = true;
        public boolean disableAntiSpoof = false;
        public int tunnelKey;
        public UUID inboundFilter;
        public UUID outboundFilter;
        public UUID vxLanPortId;
        public List<UUID> vxLanPortIds;
        public Map<String, String> properties = new HashMap<>();

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            Data that = (Data) o;

            return tunnelKey == that.tunnelKey &&
                    adminStateUp == that.adminStateUp &&
                    disableAntiSpoof == that.disableAntiSpoof &&
                    Objects.equals(inboundFilter, that.inboundFilter) &&
                    Objects.equals(outboundFilter, that.outboundFilter) &&
                    Objects.equals(vxLanPortId, that.vxLanPortId) &&
                    Objects.equals(vxLanPortIds, that.vxLanPortIds) &&
                    Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tunnelKey, adminStateUp, inboundFilter,
                                outboundFilter, vxLanPortId, vxLanPortIds,
                                name, disableAntiSpoof);
        }

        @Override
        public String toString() {
            return "Bridge.Data{" + "tunnelKey=" + tunnelKey +
                   ", inboundFilter=" + inboundFilter +
                   ", outboundFilter=" + outboundFilter +
                   ", vxLanPortId=" + vxLanPortId +
                   ", vxLanPortIds=" + vxLanPortIds +
                   ", name=" + name +
                   ", disableAntiSpoof =" + disableAntiSpoof +
                   ", adminStateUp=" + adminStateUp + '}';
        }
    }
}
