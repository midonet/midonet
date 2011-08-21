package com.midokura.midolman.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import com.midokura.midolman.layer3.Route;

public class PortDirectory {

    public static Random random = new Random();
    public static UUID generate32BitUUID() {
        // TODO: make this non-static and use ZK to generate sequence numbers.
        int r = random.nextInt();
        return new UUID(0, (long)r);
    }

    public static int UUID32toInt(UUID id) {
        long lBits = id.getLeastSignificantBits();
        if (0 != id.getMostSignificantBits() || 
                lBits < Integer.MIN_VALUE || lBits > Integer.MAX_VALUE) {
            throw new IllegalArgumentException
                ("uuid cannot be converted to int without losing information.");
        }
        return (int) lBits;
    }

    public static UUID intTo32BitUUID(int id) {
        return new UUID(0, (long)id);
    }

    public static abstract class PortConfig implements Serializable {
        private static final long serialVersionUID = 3124283622213097848L;

        private PortConfig(UUID device_id) {
            super();
            this.device_id = device_id;
        }

        public UUID device_id;
    }

    public static class BridgePortConfig extends PortConfig implements
            Serializable {
        private static final long serialVersionUID = -7817609888045028903L;

        public BridgePortConfig(UUID device_id) {
            super(device_id);
        }

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

    public static abstract class RouterPortConfig extends PortConfig implements
            Serializable {
        private static final long serialVersionUID = -4536197977961670285L;
        public int nwAddr;
        public int nwLength;
        public int portAddr;
        // Routes are stored in a ZK sub-directory. Don't serialize them.
        public transient Set<Route> routes;

        public RouterPortConfig(UUID device_id, int networkAddr,
                int networkLength, int portAddr, Set<Route> routes) {
            super(device_id);
            this.nwAddr = networkAddr;
            this.nwLength = networkLength;
            this.portAddr = portAddr;
            this.routes = new HashSet<Route>(routes);
        }

        private void readObject(java.io.ObjectInputStream stream)
                throws IOException, ClassNotFoundException {
            stream.defaultReadObject();
            int numRoutes = stream.readInt();
            routes = new HashSet<Route>();
            for (int i = 0; i < numRoutes; i++)
                routes.add((Route) stream.readObject());
        }

        private void writeObject(java.io.ObjectOutputStream stream)
                throws IOException {
            stream.defaultWriteObject();
            stream.writeInt(routes.size());
            for (Route rt : routes)
                stream.writeObject(rt);
        }
    }

    public static class LogicalRouterPortConfig extends RouterPortConfig
            implements Serializable {
        private static final long serialVersionUID = 1576824002284331148L;
        public UUID peer_uuid;

        public LogicalRouterPortConfig(UUID device_id, int networkAddr,
                int networkLength, int portAddr, Set<Route> routes,
                UUID peer_uuid) {
            super(device_id, networkAddr, networkLength, portAddr, routes);
            this.peer_uuid = peer_uuid;
        }

        @Override
        public boolean equals(Object other) {
            if (other == null)
                return false;
            if (other == this)
                return true;
            if (!(other instanceof LogicalRouterPortConfig))
                return false;
            LogicalRouterPortConfig port = (LogicalRouterPortConfig) other;
            return device_id.equals(port.device_id)
                    && nwAddr == port.nwAddr
                    && nwLength == port.nwLength
                    && peer_uuid.equals(port.peer_uuid)
                    && portAddr == port.portAddr
                    && routes.equals(port.routes);
        }
    }

    public static class MaterializedRouterPortConfig extends RouterPortConfig
            implements Serializable {
        private static final long serialVersionUID = 3050185323095662934L;
        public int localNwAddr;
        public int localNwLength;
        public transient Set<BGP> bgps;

        public MaterializedRouterPortConfig(UUID device_id,
                int networkAddr, int networkLength,
                int portAddr, Set<Route> routes,
                int localNetworkAddr, int localNetworkLength,
                Set<BGP> bgps) {
            super(device_id, networkAddr, networkLength, portAddr, routes);
            this.localNwAddr = localNetworkAddr;
            this.localNwLength = localNetworkLength;
            this.bgps = bgps;
        }

        @Override
        public boolean equals(Object other) {
            if (other == null)
                return false;
            if (other == this)
                return true;
            if (!(other instanceof MaterializedRouterPortConfig))
                return false;
            MaterializedRouterPortConfig port = 
                    MaterializedRouterPortConfig.class.cast(other);
            return device_id.equals(port.device_id)
                    && nwAddr == port.nwAddr
                    && nwLength == port.nwLength
                    && portAddr == port.portAddr
                    && routes.equals(port.routes) && bgps.equals(port.bgps)
                    && localNwAddr == port.localNwAddr
                    && localNwLength == port.localNwLength;
        }

        private void readObject(java.io.ObjectInputStream stream)
                throws IOException, ClassNotFoundException {
            stream.defaultReadObject();
            int numBGP = stream.readInt();
            bgps = new HashSet<BGP>();
            for (int i = 0; i < numBGP; i++)
                bgps.add((BGP) stream.readObject());
        }

        private void writeObject(java.io.ObjectOutputStream stream)
                throws IOException {
            stream.defaultWriteObject();
            stream.writeInt(bgps.size());
            for (BGP bgp : bgps)
                stream.writeObject(bgp);
        }
    }

    Directory dir;

    public PortDirectory(Directory dir) {
        this.dir = dir;
    }

    public void addPort(UUID portId, PortConfig port) throws IOException,
            KeeperException, InterruptedException {
        if (!(port instanceof BridgePortConfig
                || port instanceof LogicalRouterPortConfig 
                || port instanceof MaterializedRouterPortConfig))
            throw new IllegalArgumentException("Unrecognized port type.");
        byte[] data = portToBytes(port);
        dir.add("/" + portId.toString(), data, CreateMode.PERSISTENT);
        if (port instanceof RouterPortConfig) {
            String path = new StringBuilder("/").append(portId.toString())
                    .append("/routes").toString();
            dir.add(path, null, CreateMode.PERSISTENT);
            for (Route rt : ((RouterPortConfig) port).routes) {
                dir.add(path + "/" + rt.toString(), null, CreateMode.PERSISTENT);
            }
        }
    }
    
    public boolean exists(UUID portId) throws KeeperException, InterruptedException {
        return dir.has("/" + portId.toString());
    }

    public void addRoutes(UUID portId, Set<Route> routes) throws IOException,
            ClassNotFoundException, KeeperException, InterruptedException {
        PortConfig port = getPortConfigNoRoutes(portId, null);
        if (!(port instanceof RouterPortConfig))
            throw new IllegalArgumentException(
                    "Routes may only be added to a Router port");
        String routesPath = new StringBuilder("/").append(portId.toString())
                .append("/routes").toString();
        for (Route rt : routes)
            dir.add(routesPath + "/" + rt.toString(), null,
                    CreateMode.PERSISTENT);
    }

    public void removeRoutes(UUID portId, Set<Route> routes)
            throws IOException, ClassNotFoundException, KeeperException,
            InterruptedException {
        PortConfig port = getPortConfigNoRoutes(portId, null);
        if (!(port instanceof RouterPortConfig))
            throw new IllegalArgumentException(
                    "Routes may only be removed from a Router port");
        String routesPath = new StringBuilder("/").append(portId.toString())
                .append("/routes").toString();
        for (Route rt : routes)
            dir.delete(routesPath + "/" + rt.toString());
    }

    public Set<Route> getRoutes(UUID portId, Runnable routesWatcher)
            throws KeeperException, InterruptedException {
        String path = new StringBuilder("/").append(portId.toString())
                .append("/routes").toString();
        Set<String> rtStrings = dir.getChildren(path, routesWatcher);
        Set<Route> routes = new HashSet<Route>();
        for (String rtStr : rtStrings)
            routes.add(Route.fromString(rtStr));
        return routes;
    }

    private byte[] portToBytes(PortConfig port) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeObject(port);
        out.close();
        return bos.toByteArray();
    }

    public void updatePort(UUID portId, PortConfig newPort) throws IOException,
            ClassNotFoundException, KeeperException, InterruptedException {
        PortConfig oldPort = getPortConfig(portId, null, null);
        if (oldPort.getClass() != newPort.getClass())
            throw new IllegalArgumentException(
                    "Cannot change a port's type without first deleting it.");
        byte[] portData = portToBytes(newPort);
        dir.update("/" + portId.toString(), portData);
        if (newPort instanceof RouterPortConfig) {
            RouterPortConfig newRtrPort = RouterPortConfig.class.cast(newPort);
            RouterPortConfig oldRtrPort = RouterPortConfig.class.cast(oldPort);
            String routesPath = new StringBuilder("/")
                    .append(portId.toString()).append("/routes").toString();
            for (Route rt : newRtrPort.routes) {
                if (!oldRtrPort.routes.contains(rt))
                    dir.add(routesPath + "/" + rt.toString(), null,
                            CreateMode.PERSISTENT);
            }
            for (Route rt : oldRtrPort.routes) {
                if (!newRtrPort.routes.contains(rt))
                    dir.delete(routesPath + "/" + rt.toString());
            }
        }
    }

    public PortConfig getPortConfigNoRoutes(UUID portId, Runnable portWatcher)
            throws IOException, ClassNotFoundException, KeeperException,
            InterruptedException {
        byte[] data = dir.get("/" + portId.toString(), portWatcher);
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        ObjectInputStream in = new ObjectInputStream(bis);
        PortConfig port = (PortConfig) in.readObject();
        return port;
    }

    public PortConfig getPortConfig(UUID portId, Runnable portWatcher,
            Runnable routesWatcher) throws IOException, ClassNotFoundException,
            KeeperException, InterruptedException {
        PortConfig port = getPortConfigNoRoutes(portId, portWatcher);
        if (port instanceof RouterPortConfig) {
            ((RouterPortConfig) port).routes = getRoutes(portId, routesWatcher);
        } else if (routesWatcher != null)
            throw new IllegalArgumentException(
                    "Can't watch routes on a bridge port");
        return port;
    }

    public void deletePort(UUID portId) throws KeeperException,
            InterruptedException {
        String routesPath = new StringBuilder("/").append(portId.toString())
                .append("/routes").toString();
        try {
            Set<String> routes = dir.getChildren(routesPath, null);
            for (String rt : routes)
                dir.delete(routesPath + "/" + rt);
        } catch (KeeperException.NoNodeException e) {
            // Ignore the exception - the port may not have routes.
        }
        dir.delete("/" + portId.toString());
    }
}
