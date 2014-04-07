/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.cluster.data.l4lb;

import org.midonet.cluster.data.Entity;

import java.util.Objects;
import java.util.UUID;

public class LoadBalancer
        extends Entity.Base<UUID, LoadBalancer.Data, LoadBalancer> {

    public LoadBalancer() {
        this(null, new Data());
    }

    public LoadBalancer(UUID id){
        this(id, new Data());
    }

    public LoadBalancer(Data data) {
        this(null, data);
    }

    public LoadBalancer(UUID id, Data data) {
        super(id, data);
    }

    @Override
    protected LoadBalancer self() {
        return this;
    }

    public LoadBalancer setAdminStateUp(boolean adminStateUp) {
        getData().adminStateUp = adminStateUp;
        return self();
    }

    public boolean isAdminStateUp() {
        return getData().adminStateUp;
    }

    public LoadBalancer setRouterId(UUID routerId) {
        getData().routerId = routerId;
        return self();
    }

    public UUID getRouterId() {
        return getData().routerId;
    }

    public static class Data {
        private UUID routerId;
        private boolean adminStateUp = true;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass())
                return false;
            Data data = (Data) o;

            return adminStateUp == data.adminStateUp;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(adminStateUp);
        }
    }
}
