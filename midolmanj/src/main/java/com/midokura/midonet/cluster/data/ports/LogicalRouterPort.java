/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data.ports;

import java.util.UUID;
import javax.annotation.Nonnull;

import com.midokura.midonet.cluster.data.Router;

/**
 * This is a {@link RouterPort} that represents a logical connection.
 */
public class LogicalRouterPort
    extends RouterPort<LogicalRouterPort.Data, LogicalRouterPort>{

    public LogicalRouterPort(Router router, UUID uuid, Data data) {
        super(router, uuid, data);
    }

    public LogicalRouterPort(UUID uuid, Data data) {
        this(null, uuid, data);
    }

    public LogicalRouterPort(@Nonnull Data data) {
        this(null, null, data);
    }

    public LogicalRouterPort(Router router) {
        this(router, null, new Data());
    }

    public LogicalRouterPort() {
        this(null, null, new Data());
    }

    @Override
    protected LogicalRouterPort self() {
        return this;
    }

    public LogicalRouterPort setPeerId(UUID peerId) {
        getData().peer_uuid = peerId;
        return self();
    }

    public UUID getPeerId() {
        return getData().peer_uuid;
    }

    public static class Data extends RouterPort.Data {
        public UUID peer_uuid;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;

            Data data = (Data) o;

            if (peer_uuid != null ? !peer_uuid.equals(
                data.peer_uuid) : data.peer_uuid != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + (peer_uuid != null ? peer_uuid.hashCode() : 0);
            return result;
        }
    }
}
