package com.midokura.midolman.state;

import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.util.Net;

public class PortDirectory {

    public static class BridgePortConfig extends PortConfig {
        public BridgePortConfig(UUID device_id) {
            super(device_id);
        }
        // Default constructor for the Jackson deserialization.
        private BridgePortConfig() { super(); }
    
        @Override
        public boolean equals(Object other) {
            if (other == null)
                return false;
            if (other == this)
                return true;
            if (!(other instanceof BridgePortConfig))
                return false;
            BridgePortConfig port = (BridgePortConfig) other;
            return this.device_id.equals(port.device_id);
        }
    }

    public static abstract class RouterPortConfig extends PortConfig {
        public int nwAddr;
        public int nwLength;
        public transient int portAddr;
        // Routes are stored in a ZK sub-directory. Don't serialize them.        
        public transient Set<Route> routes;
    
        public RouterPortConfig(UUID device_id, int networkAddr,
                int networkLength, int portAddr, Set<Route> routes) {
            super(device_id);
            this.nwAddr = networkAddr;
            this.nwLength = networkLength;
            this.portAddr = portAddr;
            this.routes = routes;
        }
    
        // Default constructor for the Jackson deserialization.
        public RouterPortConfig() { super(); }
    
        // Setter and getter for the transient property.
        public String getPortAddr() {
            return Net.convertIntAddressToString(this.portAddr);
        }
        public void setPortAddr(String addr) {
            this.portAddr = Net.convertStringAddressToInt(addr);
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

    public static class LogicalRouterPortConfig extends RouterPortConfig {
        public UUID peer_uuid;
        
        public LogicalRouterPortConfig(UUID device_id, int networkAddr,
                int networkLength, int portAddr, Set<Route> routes,
                UUID peer_uuid) {
            super(device_id, networkAddr, networkLength, portAddr, routes);
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

    public static class MaterializedRouterPortConfig extends RouterPortConfig {
        public int localNwAddr;
        public int localNwLength;
        public transient Set<BGP> bgps;
        
        public MaterializedRouterPortConfig(UUID device_id, int networkAddr,
                int networkLength, int portAddr, Set<Route> routes,
                int localNetworkAddr, int localNetworkLength, Set<BGP> bgps) {
            super(device_id, networkAddr, networkLength, portAddr, routes);
            this.localNwAddr = localNetworkAddr;
            this.localNwLength = localNetworkLength;
            setBgps(bgps);
        }
    
        // Default constructor for the Jackson deserialization
        public MaterializedRouterPortConfig() { super(); }
    
        // Getter and setter for the Jackson deserialization
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
            return device_id.equals(port.device_id) && nwAddr == port.nwAddr
                    && nwLength == port.nwLength && portAddr == port.portAddr
                    && getRoutes().equals(port.getRoutes())
                    && getBgps().equals(port.getBgps())
                    && localNwAddr == port.localNwAddr
                    && localNwLength == port.localNwLength;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("MaterializedRouterPort [");
            sb.append(super.toString());
            sb.append(", localNwAddr=").append(IPv4.fromIPv4Address(localNwAddr));
            sb.append(", localNwLength=").append(localNwLength);
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
