/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data;

import java.util.UUID;
import javax.annotation.Nonnull;

import org.codehaus.jackson.annotate.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
              include = JsonTypeInfo.As.PROPERTY, property = "type")
public class BridgeName extends Entity.Base<String, BridgeName.Data, BridgeName>{

    public BridgeName(String key, Data data) {
        super(key, data);
    }

    public BridgeName(@Nonnull Bridge bridge) {
        super(bridge.getData().name, new Data());

        setBridgeId(bridge.getId());
    }

    public BridgeName setBridgeId(UUID bridgeId) {
        getData().id = bridgeId;
        return self();
    }

    public UUID getBridgeId() {
        return getData().id;
    }

    @Override
    protected BridgeName self() {
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
