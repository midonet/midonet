/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data.ports;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.midokura.midonet.cluster.data.Bridge;
import com.midokura.midonet.cluster.data.Port;

/**
 * A {@link BridgePort} which is materialized on a certain host.
 */
public class MaterializedBridgePort
    extends BridgePort<MaterializedBridgePort.Data, MaterializedBridgePort> {

    public MaterializedBridgePort(Bridge bridge, UUID uuid, Data data) {
        super(bridge, uuid, data);
    }

    public MaterializedBridgePort(UUID uuid, Data data) {
        this(null, uuid, data);
    }

    public MaterializedBridgePort(@Nonnull Data data) {
        this(null, null, data);
    }
    public MaterializedBridgePort(Bridge bridge) {
        this(bridge, null, new Data());
    }

    public MaterializedBridgePort() {
        this(null, null, new Data());
    }

    @Override
    protected MaterializedBridgePort self() {
        return this;
    }

    public UUID getHostId() {
        return getData().hostId;
    }

    public MaterializedBridgePort setHostId(UUID hostId) {
        getData().hostId = hostId;
        return self();
    }

    public String getInterfaceName() {
        return getData().interfaceName;
    }

    public MaterializedBridgePort setInterfaceName(String interfaceName) {
        getData().interfaceName = interfaceName;
        return self();
    }

    public static class Data extends Port.Data {
        public UUID hostId;
        public String interfaceName;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;

            Data data = (Data) o;

            if (hostId != null ? !hostId.equals(
                data.hostId) : data.hostId != null)
                return false;
            if (interfaceName != null ? !interfaceName.equals(
                data.interfaceName) : data.interfaceName != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + (hostId != null ? hostId.hashCode() : 0);
            result = 31 * result + (interfaceName != null ? interfaceName.hashCode() : 0);
            return result;
        }
    }
}
