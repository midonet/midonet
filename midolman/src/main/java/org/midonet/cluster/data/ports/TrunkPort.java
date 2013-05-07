/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.data.ports;

import java.util.UUID;
import javax.annotation.Nonnull;

import org.midonet.cluster.data.Port;

/**
 * A {@link VlanBridgePort} which is materialized on a certain host.
 */
public class TrunkPort
    extends VlanBridgePort<TrunkPort.Data, TrunkPort> {

    public TrunkPort(UUID bridgeId, UUID uuid, UUID peerId, Data data) {
        super(bridgeId, uuid, data);
        this.setPeerId(peerId);
    }

    public TrunkPort(UUID uuid, UUID peerId, Data data) {
        this(null, uuid, peerId,  data);
    }

    public TrunkPort(@Nonnull Data data) {
        this(null, null, null, data);
    }

    public TrunkPort() {
        this(null, null, null, new Data());
    }

    @Override
    protected TrunkPort self() {
        return this;
    }

    public UUID getHostId() {
        return getData().hostId;
    }

    public TrunkPort setHostId(UUID hostId) {
        getData().hostId = hostId;
        return self();
    }

    public UUID getPeerId() {
        return getData().peerId;
    }

    public TrunkPort setPeerId(UUID peerId) {
        getData().peerId = peerId;
        return self();
    }

    public String getInterfaceName() {
        return getData().interfaceName;
    }

    public TrunkPort setInterfaceName(String interfaceName) {
        getData().interfaceName = interfaceName;
        return self();
    }

    public static class Data extends Port.Data {
        public UUID hostId;
        public UUID peerId;
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
            if (peerId != null ? !peerId.equals(
                data.peerId) : data.peerId != null)
                return false;
            if (interfaceName != null ? !interfaceName.equals(
                data.interfaceName) : data.interfaceName != null) return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + (hostId != null ? hostId.hashCode() : 0);
            result = 31 * result + (peerId != null ? peerId.hashCode() : 0);
            result = 31 * result + (interfaceName != null ? interfaceName.hashCode() : 0);
            return result;
        }
    }
}
