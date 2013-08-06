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

    public static class BridgePortConfig extends PortConfig {
        public Short vlanId;

        public BridgePortConfig(UUID device_id) {
            super(device_id);
        }

        public BridgePortConfig(UUID deviceId, UUID peerId, Short vlanId) {
            this.device_id = deviceId;
            this.peerId = peerId;
            this.vlanId = vlanId;
        }

        // Default constructor for the Jackson deserialization.
        public BridgePortConfig() { super(); }

        public Short getVlanId() { return vlanId; }
        public void setVlanId(Short vlanId) {
            this.vlanId = vlanId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;
            BridgePortConfig that = (BridgePortConfig) o;
            if (vlanId != null ? !vlanId.equals(that.vlanId) :
                    that.vlanId != null)
                return false;
            return true;
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + (vlanId != null ? vlanId.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return "BridgePortConfig{peer_uuid=" + peerId +
                    ", vlanId = " + vlanId + "}";
        }
    }

    public static class RouterPortConfig extends PortConfig {
        // TODO(pino): use IntIPv4 for Babuza!
        public int nwAddr;
        public int nwLength;
        public int portAddr;
        public MAC hwAddr;
        public transient Set<BGP> bgps;

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

        public RouterPortConfig(UUID device_id,
                                int networkAddr,
                                int networkLength,
                                int portAddr, MAC mac,
                                Set<Route> routes,
                                Set<BGP> bgps) {
            this(device_id, networkAddr, networkLength, portAddr, routes, mac);
            setBgps(bgps);
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

        public MAC getHwAddr() { return hwAddr; }

        public void setHwAddr(MAC hwAddr) { this.hwAddr = hwAddr; }

        public Set<Route> getRoutes() { return routes; }
        public void setRoutes(Set<Route> routes) { this.routes = routes; }

        public Set<BGP> getBgps() { return bgps; }
        public void setBgps(Set<BGP> bgps) { this.bgps = bgps; }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("RouterPort [");
            sb.append("nwAddr=").append(IPv4.fromIPv4Address(nwAddr));
            sb.append(", nwLength=").append(nwLength);
            sb.append(", portAddr=").append(IPv4.fromIPv4Address(portAddr));
            sb.append(", bgps={");
            if (null != bgps) {
                for (BGP b : bgps)
                    sb.append(b.toString());
            }
            sb.append("}]");
            return sb.toString();
        }

        @Override
        public boolean equals(Object other) {
            if (other == null)
                return false;
            if (other == this)
                return true;
            if (!(other instanceof RouterPortConfig))
                return false;
            RouterPortConfig that = (RouterPortConfig) other;
            return device_id.equals(that.device_id)
                    && nwAddr == that.nwAddr
                    && nwLength == that.nwLength
                    && portAddr == that.portAddr
                    && hwAddr == null ? that.getHwAddr() == null :
                       hwAddr.equals(that.getHwAddr())
                    && getRoutes() == null ? that.getRoutes() == null :
                       getRoutes().equals(that.getRoutes())
                    && getBgps() == null ? that.getBgps() == null :
                       getBgps().equals(that.getBgps());
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + nwAddr;
            result = 31 * result + nwLength;
            result = 31 * result + portAddr;
            result = 31 * result + (hwAddr != null ? hwAddr.hashCode() : 0);
            result = 31 * result + (bgps != null ? bgps.hashCode() : 0);
            return result;
        }
    }


    @Deprecated //TODO delete
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

    @Deprecated //TODO delete
    public static class LogicalVlanBridgePortConfig
            extends VlanBridgePortConfig {

        private Short vlanId;
        private UUID peerId;

        public LogicalVlanBridgePortConfig() {
            super();
        }

        public LogicalVlanBridgePortConfig(UUID deviceId,
                                           UUID peerId,
                                           Short vlanId) {
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

    @Deprecated //TODO delete
    public static class TrunkVlanBridgePortConfig
        extends VlanBridgePortConfig {

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


}
