package com.midokura.midolman.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NoChildrenForEphemeralsException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;

public class PortDirectory {

    private class PortConfig implements Serializable {
        UUID device_id;
    }

    public class BridgePortConfig extends PortConfig implements Serializable {
    }

    private class RouterPortConfig extends PortConfig implements Serializable {
        InetAddress networkAddr;
        byte networkLength;
        InetAddress portAddr;
        // Routes are stored in a ZK sub-directory. Don't serialize them.
        transient Set<Route> routes;
    }

    public class LogicalRouterPortConfig extends PortConfig implements
            Serializable {
        UUID peer_uuid;
    }

    public class MaterializedRouterPortConfig extends PortConfig implements
            Serializable {
        InetAddress localNetworkAddr;
        byte localNetworkLength;
        transient Set<BGP> bgps;

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

    public void addPort(UUID portId, PortConfig port) throws NoNodeException,
            NodeExistsException, NoChildrenForEphemeralsException, IOException {
        byte[] data = portToBytes(port);
        dir.add("/" + portId.toString(), data, CreateMode.PERSISTENT);
        if (port instanceof RouterPortConfig) {
            String path = new StringBuilder("/").append(portId.toString())
                    .append("/routes").toString();
            for (Route rt : ((RouterPortConfig) port).routes) {
                dir.add(path + "/" + rt.toString(), null, CreateMode.PERSISTENT);
            }
        }
    }

    public void addRoutes(UUID portId, Set<Route> routes)
            throws NoNodeException, IOException, ClassNotFoundException,
            NodeExistsException, NoChildrenForEphemeralsException {
        PortConfig port = _getPortNoRoutes(portId, null, null);
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
            throws NoNodeException, IOException, ClassNotFoundException {
        PortConfig port = _getPortNoRoutes(portId, null, null);
        if (!(port instanceof RouterPortConfig))
            throw new IllegalArgumentException(
                    "Routes may only be removed from a Router port");
        String routesPath = new StringBuilder("/").append(portId.toString())
                .append("/routes").toString();
        for (Route rt : routes)
            dir.delete(routesPath + "/" + rt.toString());
    }

    private byte[] portToBytes(PortConfig port) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeObject(port);
        out.close();
        return bos.toByteArray();
    }

    public void updatePort(UUID portId, PortConfig newPort)
            throws NoNodeException, IOException, ClassNotFoundException,
            NodeExistsException, NoChildrenForEphemeralsException {
        PortConfig oldPort = getPortConfiguration(portId, null, null);
        if (oldPort.getClass() != newPort.getClass())
            throw new IllegalArgumentException(
                    "Cannot change a port's type without first deleting it.");
        byte[] portData = portToBytes(newPort);
        dir.update("/" + portId.toString(), portData);
        if (newPort instanceof RouterPortConfig) {
            RouterPortConfig newRtrPort = (RouterPortConfig)newPort;
            RouterPortConfig oldRtrPort = (RouterPortConfig)oldPort;
            String routesPath = new StringBuilder("/").
                    append(portId.toString()).append("/routes").toString();
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

    private PortConfig _getPortNoRoutes(UUID portId, Runnable portWatcher,
            Runnable routesWatcher) throws NoNodeException, IOException,
            ClassNotFoundException {
        byte[] data = dir.get("/" + portId.toString(), portWatcher);
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        ObjectInputStream in = new ObjectInputStream(bis);
        PortConfig port = (PortConfig) in.readObject();
        return port;
    }

    public PortConfig getPortConfiguration(UUID portId, Runnable portWatcher,
            Runnable routesWatcher) throws NoNodeException, IOException,
            ClassNotFoundException {
        PortConfig port = _getPortNoRoutes(portId, portWatcher,
                routesWatcher);
        if (port instanceof RouterPortConfig) {
            String path = new StringBuilder("/").append(portId.toString())
                    .append("/routes").toString();
            Set<String> routes = dir.getChildren(path, routesWatcher);
            ((RouterPortConfig) port).routes = new HashSet<Route>();
            for (String rt : routes)
                ((RouterPortConfig) port).routes.add(Route.fromString(rt));
        }
        return port;
    }

    public void deletePort(UUID portId) throws NoNodeException {
        String routesPath = new StringBuilder("/").append(portId.toString())
                .append("/routes").toString();
        try {
            Set<String> routes = dir.getChildren(routesPath, null);
            for (String rt : routes)
                dir.delete(routesPath + "/" + rt);
        } catch (NoNodeException e) {
        }
        dir.delete("/" + portId.toString());
    }
}
