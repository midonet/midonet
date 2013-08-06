package org.midonet.cluster.data.ports;

import java.util.UUID;
import javax.annotation.Nonnull;

public class LogicalVlanBridgePort
    extends VlanBridgePort<LogicalVlanBridgePort.Data, LogicalVlanBridgePort> {

    public LogicalVlanBridgePort(UUID bridgeId, UUID uuid, Data data) {
        super(bridgeId, uuid, data);
    }

    public LogicalVlanBridgePort(UUID uuid, Data data) {
        this(null, uuid, data);
    }

    public LogicalVlanBridgePort(@Nonnull Data data) {
        this(null, null, data);
    }

    public LogicalVlanBridgePort() {
        this(null, null, new Data());
    }

    @Override
    protected LogicalVlanBridgePort self() {
        return this;
    }

    @Override
    public LogicalVlanBridgePort setPeerId(UUID peerId) {
        getData().peer_uuid = peerId;
        return self();
    }

    @Override
    public UUID getPeerId() {
        return getData().peer_uuid;
    }

    public LogicalVlanBridgePort setVlanId(Short vlanId) {
        this.getData().vlanId = vlanId;
        return this;
    }

    public Short getVlanId() {
        return this.getData().vlanId;
    }

    public static class Data extends BridgePort.Data {

        Short vlanId; // actually 10 bits as per IIEE 802.1Q

        public LogicalVlanBridgePort.Data setVlanId(Short id) {
            this.vlanId = id;
            return this;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Data)) return false;
            if (!super.equals(o)) return false;

            Data data = (Data) o;

            if (vlanId != null ? !vlanId.equals(
                data.vlanId) : data.vlanId != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + (vlanId != null ? vlanId.hashCode() : 0);
            return result;
        }
    }

}
