/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.cluster.data;

import org.codehaus.jackson.annotate.JsonTypeInfo;

import javax.annotation.Nonnull;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
              include = JsonTypeInfo.As.PROPERTY, property = "type")
public class PortGroupName extends Entity.Base<String, PortGroupName.Data, PortGroupName>{

    public PortGroupName(String key, Data data) {
        super(key, data);
    }

    public PortGroupName(@Nonnull PortGroup portGroup) {
        super(portGroup.getData().name, new Data());

        setPortGroupId(portGroup.getId());
    }

    public PortGroupName setPortGroupId(UUID portGroupId) {
        getData().id = portGroupId;
        return self();
    }

    public UUID getPortGroupId() {
        return getData().id;
    }

    @Override
    protected PortGroupName self() {
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
