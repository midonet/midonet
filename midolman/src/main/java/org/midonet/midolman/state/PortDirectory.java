/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.state;

import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.midonet.cluster.data.BGP;
import org.midonet.midolman.layer3.Route;
import org.midonet.packets.IPv4;
import org.midonet.packets.MAC;
import org.midonet.packets.Net;

// These representations are being deprecated in favor of classes defined in
// cluster client.
@Deprecated
public class PortDirectory {
    public static Random rand = new Random(System.currentTimeMillis());

    public static abstract class VlanBridgePortConfig extends PortConfig {
        public VlanBridgePortConfig(UUID device_id) {
            super(device_id);
        }

        // Default constructor for the Jackson deserialization.
        public VlanBridgePortConfig() { super(); }

        @Override
        public boolean equals(Object other) {
            if (other == null)
                return false;
            if (other == this)
                return true;
            if (!(other instanceof VlanBridgePortConfig))
                return false;
            return super.equals(other);
        }
    }

    public static abstract class BridgePortConfig extends PortConfig {
        public BridgePortConfig(UUID device_id) {
            super(device_id);
        }

        // Default constructor for the Jackson deserialization.
        public BridgePortConfig() { super(); }

        @Override
        public boolean equals(Object other) {
            if (other == null)
                return false;
            if (other == this)
                return true;
            if (!(other instanceof BridgePortConfig))
                return false;
            return super.equals(other);
        }
    }

    public static abstract class RouterPortConfig extends PortConfig {
        // TODO(pino): use IntIPv4 for Babuza!
        public int nwAddr;
        public int nwLength;
        public int portAddr;
        public MAC hwAddr;

        // Routes are stored in a ZK sub-directory. Don't serialize them.
        public transient Set<Route> routes;

        public RouterPortConfig(UUID device_id, int networkAddr,
                int networkLength, int portAddr, Set<Route> routes,
                MAC mac) {
            super(device_id);
            this.nwAddr = networkAddr;
            this.nwLength = networkLength;
            this.portAddr = portAddr;
            this.routes = routes;
            if (mac == null) {
                initializeHwAddr();
            } else
                this.hwAddr = mac;
        }

        // Default constructor for the Jackson deserialization.
        public RouterPortConfig() {
            super();
            initializeHwAddr();
        }

        private void initializeHwAddr() {
            // TODO: Use the midokura OUI.
            byte[] macBytes = new byte[6];
            rand.nextBytes(macBytes);
            macBytes[0] = 0x02;
            this.hwAddr = new MAC(macBytes);
        }

        // Custom accessors for Jackson serialization

        public String getNwAddr() {
            return Net.convertIntAddressToString(this.nwAddr);
        }

        public void setNwAddr(String addr) {
            this.nwAddr = Net.convertStringAddressToInt(addr);
        }

        public String getPortAddr() {
            return Net.convertIntAddressToString(this.portAddr);
        }

        public void setPortAddr(String addr) {
            this.portAddr = Net.convertStringAddressToInt(addr);
        }

        public MAC getHwAddr() {
            return hwAddr;
        }

        public void setHwAddr(MAC hwAddr) {
            this.hwAddr = hwAddr;
        }

        public Set<Route> getRoutes() { return routes; }
        public void setRoutes(Set<Route> routes) { this.routes = routes; }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("nwAddr=").append(IPv4.fromIPv4Address(nwAddr));
            sb.append(", nwLength=").append(nwLength);
            sb.append(", portAddr=").append(IPv4.fromIPv4Address(portAddr));
            return sb.toString();
        }
    }

    public static class LogicalBridgePortConfig
            extends BridgePortConfig implements LogicalPortConfig {
        public UUID peer_uuid;
        public Short vlanId;

        public UUID peerId() { return peer_uuid; }
        public Short vlanId() { return vlanId; }

        @Override
        public void setPeerId(UUID id) {
            peer_uuid = id;
        }

        public void setVlanId(Short vlanId) {
            this.vlanId = vlanId;
        }

        // Default constructor for the Jackson deserialization.
        public LogicalBridgePortConfig() { super(); }

        public LogicalBridgePortConfig(UUID device_id, UUID peer_uuid, Short vlanId) {
            super(device_id);
            this.peer_uuid = peer_uuid;
            this.vlanId = vlanId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;

            LogicalBridgePortConfig that = (LogicalBridgePortConfig) o;

            if (peer_uuid != null ? !peer_uuid.equals(that.peer_uuid) :
                    that.peer_uuid != null)
                return false;
            if (vlanId != null ? !vlanId.equals(that.vlanId) :
                that.vlanId != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + (peer_uuid != null ? peer_uuid.hashCode() : 0);
            result = 31 * result + (vlanId != null ? vlanId.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "LogicalBridgePortConfig{peer_uuid=" + peer_uuid +
                   ", vlanId = " + vlanId + "}";
        }
    }

    public static class LogicalVlanBridgePortConfig extends VlanBridgePortConfig
        implements LogicalPortConfig{

        private Short vlanId;
        private UUID peerId;

        public LogicalVlanBridgePortConfig() {
            super();
        }

        public LogicalVlanBridgePortConfig(UUID deviceId, UUID peerId, Short vlanId) {
            this.device_id = deviceId;
            this.peerId = peerId;
            this.vlanId = vlanId;
        }

        public void setVlanId(Short vlanId) {
            this.vlanId = vlanId;
        }

        public UUID peerId() { return peerId; }
        public Short vlanId() { return vlanId; }

        @Override
        public void setPeerId(UUID id) {
            peerId = id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LogicalVlanBridgePortConfig)) return false;
            if (!super.equals(o)) return false;

            LogicalVlanBridgePortConfig that = (LogicalVlanBridgePortConfig) o;

            if (vlanId != null ? !vlanId.equals(
                that.vlanId) : that.vlanId != null)
                return false;

            if (peerId != null ? !peerId.equals(that.peerId) :
                that.peerId != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + (vlanId != null ? vlanId.hashCode() : 0);
            result = 31 * result + (peerId != null ? peerId.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "LogicalVlanBridgePortConfig{peer_uuid=" + peerId +
                ", vlan_id=" + vlanId + "}";
        }
    }

    public static class LogicalRouterPortConfig
            extends RouterPortConfig implements LogicalPortConfig {
        public UUID peer_uuid;

        public UUID peerId() { return peer_uuid; }

        @Override
        public void setPeerId(UUID id) {
            peer_uuid = id;
        }

        public LogicalRouterPortConfig(UUID device_id, int networkAddr,
                int networkLength, int portAddr, Set<Route> routes,
                UUID peer_uuid, MAC mac) {
            super(device_id, networkAddr, networkLength, portAddr, routes, mac);
            this.peer_uuid = peer_uuid;
        }

        // Default constructor for the Jackson deserialization.
        public LogicalRouterPortConfig() { super(); }

        @Override
        public boolean equals(Object other) {
            if (other == null)
                return false;
            if (other == this)
                return true;
            if (!(other instanceof LogicalRouterPortConfig))
                return false;
            LogicalRouterPortConfig port = (LogicalRouterPortConfig) other;
            return device_id.equals(port.device_id) && nwAddr == port.nwAddr
                    && nwLength == port.nwLength
                    && peer_uuid.equals(port.peer_uuid)
                    && portAddr == port.portAddr
                    && getRoutes().equals(port.getRoutes());
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("LogicalRouterPort [");
            sb.append(super.toString());
            sb.append(", peerId=").append(peer_uuid);
            sb.append("]");
            return sb.toString();
        }
    }


    public interface MaterializedPortConfig{
        UUID getHostId();
        String getInterfaceName();

        void setHostId(UUID id);
        void setInterfaceName(String interfaceName);
    }

    public static class MaterializedBridgePortConfig extends BridgePortConfig
        implements MaterializedPortConfig {

        public UUID hostId;
        public String interfaceName;

        public MaterializedBridgePortConfig(UUID device_id) {
            super(device_id);
        }

        // Default constructor for the Jackson deserialization
        public MaterializedBridgePortConfig() { super(); }

        public UUID getHostId() { return hostId; }

        public void setHostId(UUID hostId) {
            this.hostId = hostId;
        }

        public String getInterfaceName() { return interfaceName; }

        public void setInterfaceName(String interfaceName) {
            this.interfaceName = interfaceName;
        }

        @Override
        public boolean equals(Object other) {
            if (other == null)
                return false;
            if (other == this)
                return true;
            if (!(other instanceof MaterializedBridgePortConfig))
                return false;

            MaterializedBridgePortConfig that =
                (MaterializedBridgePortConfig) other;
            if (hostId != null ? !hostId.equals(that.hostId) :
                that.hostId != null)
                return false;

            if (interfaceName != null ?
                !interfaceName.equals(that.interfaceName) :
                that.interfaceName != null)
                return false;

            return super.equals(other);
        }
    }

    public static class TrunkVlanBridgePortConfig
        extends VlanBridgePortConfig implements MaterializedPortConfig {

        public UUID hostId;
        public String interfaceName;

        public TrunkVlanBridgePortConfig(UUID device_id) {
            super(device_id);
        }

        // Default constructor for the Jackson deserialization
        public TrunkVlanBridgePortConfig() { super(); }

        public UUID getHostId() { return hostId; }

        public void setHostId(UUID hostId) {
            this.hostId = hostId;
        }

        public String getInterfaceName() { return interfaceName; }

        public void setInterfaceName(String interfaceName) {
            this.interfaceName = interfaceName;
        }

        @Override
        public boolean equals(Object other) {
            if (other == null)
                return false;
            if (other == this)
                return true;
            if (!(other instanceof TrunkVlanBridgePortConfig))
                return false;

            TrunkVlanBridgePortConfig that =
                    (TrunkVlanBridgePortConfig) other;
            if (hostId != null ? !hostId.equals(that.hostId) :
                    that.hostId != null)
                return false;

            if (interfaceName != null ?
                    !interfaceName.equals(that.interfaceName) :
                    that.interfaceName != null)
                return false;

            return super.equals(other);
        }
    }

    public static class MaterializedRouterPortConfig extends RouterPortConfig
        implements MaterializedPortConfig {
        public UUID hostId;
        public String interfaceName;
        public transient Set<BGP> bgps;

        public MaterializedRouterPortConfig(UUID device_id, int networkAddr,
                int networkLength, int portAddr, MAC mac, Set<Route> routes,
                Set<BGP> bgps) {
            super(device_id, networkAddr, networkLength, portAddr, routes, mac);
            setBgps(bgps);
        }

        // Default constructor for the Jackson deserialization
        public MaterializedRouterPortConfig() { super(); }

        // Custom accessors for Jackson serialization
        public UUID getHostId() { return hostId; }
        public void setHostId(UUID hostId) {
            this.hostId = hostId;
        }

        public String getInterfaceName() { return interfaceName; }

        public void setInterfaceName(String interfaceName) {
            this.interfaceName = interfaceName;
        }

        public Set<BGP> getBgps() { return bgps; }
        public void setBgps(Set<BGP> bgps) { this.bgps = bgps; }

        @Override
        public boolean equals(Object other) {
            if (other == null)
                return false;
            if (other == this)
                return true;
            if (!(other instanceof MaterializedRouterPortConfig))
                return false;
            MaterializedRouterPortConfig port = MaterializedRouterPortConfig.class
                    .cast(other);

            if (hostId != null ? !hostId.equals(port.hostId) :
                    port.hostId != null)
                return false;

            if (interfaceName != null ?
                    !interfaceName.equals(port.interfaceName) :
                    port.interfaceName != null)
                return false;

            return device_id.equals(port.device_id) && nwAddr == port.nwAddr
                    && nwLength == port.nwLength && portAddr == port.portAddr
                    && (getRoutes() == null ? port.getRoutes() == null :
                            getRoutes().equals(port.getRoutes()))
                    && (getBgps() == null ? port.getBgps() == null :
                            getBgps().equals(port.getBgps()));
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("MaterializedRouterPort [");
            sb.append(super.toString());
            sb.append(", hostId=").append(hostId);
            sb.append(", interfaceName=").append(interfaceName);
            sb.append(", bgps={");
            if (null != bgps) {
                for (BGP b : bgps)
                    sb.append(b.toString());
            }
            sb.append("}]");
            return sb.toString();
        }
    }

}
