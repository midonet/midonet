/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data.ports;

import java.util.UUID;

import javax.annotation.Nonnull;

import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Port;

/**
 * This is a {@link BridgePort} that represents a logical connection.
 */
public class LogicalBridgePort
    extends BridgePort<LogicalBridgePort.Data, LogicalBridgePort>
    implements LogicalPort<LogicalBridgePort> {

    public LogicalBridgePort(UUID bridgeId, UUID uuid, Data data) {
        super(bridgeId, uuid, data);
    }

    public LogicalBridgePort(UUID uuid, Data data) {
        this(null, uuid, data);
    }

    public LogicalBridgePort(@Nonnull Data data) {
        this(null, null, data);
    }

    public LogicalBridgePort() {
        this(null, null, new Data());
    }

    @Override
    protected LogicalBridgePort self() {
        return this;
    }

    @Override
    public LogicalBridgePort setPeerId(UUID peerId) {
        getData().peer_uuid = peerId;
        return self();
    }

    @Override
    public UUID getPeerId() {
        return getData().peer_uuid;
    }

    public static class Data extends Port.Data {
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
