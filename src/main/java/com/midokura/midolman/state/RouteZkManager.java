/*
 * @(#)RouteZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.PortConfig;
import com.midokura.midolman.state.PortDirectory.RouterPortConfig;


/**
 * This class was created to handle multiple ops feature in Zookeeper.
 * 
 * @version        1.6 10 Sept 2011
 * @author         Ryu Ishimoto
 */
public class RouteZkManager extends ZkManager {

    public static class RouteRefConfig implements Serializable {

        private static final long serialVersionUID = 1L;
        public String path = null;

        public RouteRefConfig() {
        }
        
        public RouteRefConfig(String path) {
            this.path = path;
        }
    }
    
    /**
     * Constructor to set ZooKeeper and basepath.
     * @param zk  ZooKeeper object.
     * @param basePath  The root path.
     */
    public RouteZkManager(ZooKeeper zk, String basePath) {
    	super(zk, basePath);
    }
    
    /**
     * Add a new route to Zookeeper directory.
     * If the next hop is BLACKHOLE or REJECT, or the next hop is PORT and
     * the port type is LogicalRouterPort, then add it under the router.
     * Otherwise, add it under the materialized port.
     * 
     * @param id  Route UUID
     * @param routerId  Router UUID
     * @param route  Route to store as data.
     * @throws InterruptedException  Thread paused too long.
     * @throws KeeperException  Zookeeper error.
     * @throws IOException  Serialization error.
     * @throws ClassNotFoundException Class does not exist.
     */
    public void create(UUID id, UUID routerId, Route route) 
            throws InterruptedException, KeeperException, IOException, 
                ClassNotFoundException {
        List<Op> ops = new ArrayList<Op>();
        
        PortConfig port = null;
        if (route.nextHop == Route.NextHop.PORT) {
            // Check what kind of port this is.
            PortZkManager portManager = 
                new PortZkManager(zk, pathManager.getBasePath());
            port = portManager.get(route.nextHopPort);
            if (!(port instanceof RouterPortConfig)) {
                // Cannot add route to bridge ports
                throw new IllegalArgumentException(
                        "Can only add a route to a router");                
            }
        }
         
        String path = null;
        if (port instanceof MaterializedRouterPortConfig) {            
            path = pathManager.getPortRoutePath(route.nextHopPort, id);
        } else {
            path = pathManager.getRouterRoutePath(routerId, id);
        }
        ops.add(Op.create(path, serialize(route),
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Add reference to this        
        ops.add(Op.create(pathManager.getRoutePath(id), 
        		serialize(new RouteRefConfig(path)),
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        zk.multi(ops);
    }
    
    public List<Op> getRouterRouteDeleteOps(UUID id, UUID routerId) {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getRouterRoutePath(routerId, id), -1));
        ops.add(Op.delete(pathManager.getRoutePath(id), -1));
        return ops;
    }
    
    public List<Op> getPortRouteDeleteOps(UUID id, UUID portId) {
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(pathManager.getPortRoutePath(portId, id), -1));
        ops.add(Op.delete(pathManager.getRoutePath(id), -1));
        return ops;
    }
    
    public List<Op> getDeleteOps(UUID id) 
            throws KeeperException, InterruptedException, IOException {
        RouteRefConfig routeRef = getRouteRefConfig(id);
        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.delete(routeRef.path, -1));
        ops.add(Op.delete(pathManager.getRoutePath(id), -1));
        return ops;
    }
    
    public void delete(UUID id) 
            throws InterruptedException, KeeperException, IOException {
        this.zk.multi(getDeleteOps(id));
    }
    
    public RouteRefConfig getRouteRefConfig(UUID id) 
            throws KeeperException, InterruptedException, IOException {
        byte[] data = zk.getData(pathManager.getRoutePath(id), null, null);
        return deserialize(data, RouteRefConfig.class);      
    }
    
    /**
     * Get a Route object with the given id.
     * @param id  UUID of the Route object.
     * @return  Route object stored in ZK.
     * @throws KeeperException  ZooKeeper exception.
     * @throws InterruptedException  Thread paused too long.
     * @throws IOException   Serialization error.
     */
    public Route get(UUID id) 
    		throws KeeperException, InterruptedException, IOException {
        RouteRefConfig routeRef = getRouteRefConfig(id);
        byte[] routeData = zk.getData(routeRef.path, null, null);
        return deserialize(routeData, Route.class);
    }
    
    public HashMap<UUID, Route> listRouterRoutes(UUID routerId) 
            throws KeeperException, InterruptedException, IOException {
        HashMap<UUID, Route> result = new HashMap<UUID, Route>();
        List<String> routeIds = zk.getChildren(
                pathManager.getRouterRoutesPath(routerId), null);
        for (String routeId : routeIds) {
            UUID id = UUID.fromString(routeId);
            byte[] data = zk.getData(
                    pathManager.getRouterRoutePath(routerId, id), null, null);
            result.put(id, deserialize(data, Route.class));
        }
        return result;
    }
    
    public HashMap<UUID, Route> listPortRoutes(UUID portId) 
            throws KeeperException, InterruptedException, IOException {
        HashMap<UUID, Route> result = new HashMap<UUID, Route>();
        List<String> routeIds = zk.getChildren(
                pathManager.getPortRoutesPath(portId), null);
        for (String routeId : routeIds) {
            UUID id = UUID.fromString(routeId);
            byte[] data = zk.getData(pathManager.getPortRoutePath(portId, id),
                    null, null);
            result.put(id, deserialize(data, Route.class));
        }
        return result;
    }
    
    /**
     * Get a list of Route objects of a router.  With the current ZK schema,
     * this requires that it gets all the routes for a router first,
     * and then finding all the MaterializedRouterPort for that router and
     * getting all the ports' routes as well.
     * @param tenantId  Tenant UUID,
     * @return  An array of RouterConfigs
     * @throws KeeperException  Zookeeper exception.
     * @throws InterruptedException  Paused thread interrupted.
     * @throws ClassNotFoundException  Unknown class.
     * @throws IOException  Serialization error.
     */
    public HashMap<UUID, Route> list(UUID routerId) 
            throws KeeperException, InterruptedException, 
                IOException, ClassNotFoundException {
        HashMap<UUID, Route> routes = listRouterRoutes(routerId);
        List<String> portIds = zk.getChildren(
                pathManager.getRouterPortsPath(routerId), null);
        for (String portId : portIds) {
        	// For each MaterializedRouterPort, process it.
        	UUID portUUID = UUID.fromString(portId);
        	byte[] data = zk.getData(
        			pathManager.getPortPath(portUUID), null, null);
        	PortConfig port = deserialize(data, PortConfig.class);
        	if (!(port instanceof MaterializedRouterPortConfig)) {
        		continue;
        	}
        	
            HashMap<UUID, Route> portRoutes = listPortRoutes(portUUID);
            routes.putAll(portRoutes);
        }
        return routes;
    }
}
