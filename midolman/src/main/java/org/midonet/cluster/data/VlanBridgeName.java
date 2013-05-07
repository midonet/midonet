/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data;

import java.util.UUID;
import javax.annotation.Nonnull;

import org.codehaus.jackson.annotate.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
              include = JsonTypeInfo.As.PROPERTY, property = "type")
public class VlanBridgeName
    extends Entity.Base<String, VlanBridgeName.Data, VlanBridgeName>{

    public VlanBridgeName(String key, Data data) {
        super(key, data);
    }

    public VlanBridgeName(@Nonnull VlanAwareBridge bridge) {
        super(bridge.getData().name, new Data());
        setVLANBridgeId(bridge.getId());
    }

    public VlanBridgeName setVLANBridgeId(UUID bridgeId) {
        getData().id = bridgeId;
        return self();
    }

    public UUID getVlanBridgeId() {
        return getData().id;
    }

    @Override
    protected VlanBridgeName self() {
        return this;
    }

    public static class Data {
        public UUID id;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Data data = (Data) o;

            if (id != null ? !id.equals(data.id) : data.id != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            return id != null ? id.hashCode() : 0;
        }
    }
}
