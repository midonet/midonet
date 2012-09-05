/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data;

import org.codehaus.jackson.annotate.JsonTypeInfo;

import javax.annotation.Nonnull;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
              include = JsonTypeInfo.As.PROPERTY, property = "type")
public class RouterName extends Entity.Base<String, RouterName.Data, RouterName>{

    public RouterName(String key, Data data) {
        super(key, data);
    }

    public RouterName(@Nonnull Router router) {
        super(router.getData().name, new Data());

        setRouterId(router.getId());
    }

    public RouterName setRouterId(UUID routerId) {
        getData().id = routerId;
        return self();
    }

    public UUID getRouterId() {
        return getData().id;
    }

    @Override
    protected RouterName self() {
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
