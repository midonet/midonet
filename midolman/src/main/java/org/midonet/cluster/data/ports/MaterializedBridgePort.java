/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data.ports;

import java.util.UUID;

import javax.annotation.Nonnull;

import org.midonet.cluster.data.Port;

/**
 * A {@link BridgePort} which is materialized on a certain host.
 */
public class MaterializedBridgePort
    extends BridgePort<MaterializedBridgePort.Data, MaterializedBridgePort>
    implements ExteriorPort<MaterializedBridgePort> {

    public MaterializedBridgePort(UUID bridgeId, UUID uuid, Data data) {
        super(bridgeId, uuid, data);
    }

    public MaterializedBridgePort(UUID uuid, Data data) {
        this(null, uuid, data);
    }

    public MaterializedBridgePort(@Nonnull Data data) {
        this(null, null, data);
    }

    public MaterializedBridgePort() {
        this(null, null, new Data());
    }

    @Override
    protected MaterializedBridgePort self() {
        return this;
    }

    /**
     * Get the UUID of the host set in this server-side DTO object for the
     * materialized bridge port.
     *
     * @return the UUID of the host associated with this materialized bridge
     *         port
     */
    @Override
    public UUID getHostId() {
        return getData().hostId;
    }

    /**
     * Set the UUID of the host to this server-side DTO object for the
     * materialized bridge port.
     *
     * @return the UUID of the host associated with this materialized bridge
     *         port
     */
    @Override
    public MaterializedBridgePort setHostId(UUID hostId) {
        getData().hostId = hostId;
        return self();
    }

    /**
     * Get the interface name set in this server-side DTO object for the
     * materialized bridge port.
     *
     * @return the string of interface name associated with this materialized
     *         port
     */
    @Override
    public String getInterfaceName() {
        return getData().interfaceName;
    }

    /**
     * Set the interface name to this server-side DTO object for the
     * materialized bridge port.
     *
     * @param interfaceName a name of the interface to be set
     * @return              this server-side DTO object
     */
    @Override
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
