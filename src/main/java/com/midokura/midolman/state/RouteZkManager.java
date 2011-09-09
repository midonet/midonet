/*
 * @(#)RouteZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
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
import com.midokura.midolman.util.JSONSerializer;


/**
 * This class was created to handle multiple ops feature in Zookeeper.
 * 
 * @version        1.6 10 Sept 2011
 * @author         Ryu Ishimoto
 */
public class RouteZkManager {

    public static class RouteRefConfig implements Serializable {

        private static final long serialVersionUID = 1L;
        public String path = null;

        public RouteRefConfig() {
        }
        
        public RouteRefConfig(String path) {
            this.path = path;
        }
    }
    
    private ZkPathManager pathManager = null;
    private ZooKeeper zk = null;
    /**
     * Default constructor.
     * 
     * @param zk Zookeeper object.
     * @param basePath  Directory to set as the base.
     */
    public RouteZkManager(ZooKeeper zk, String basePath) {
        this.pathManager = new ZkPathManager(basePath);
        this.zk = zk;
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
                        "Cannot add route to non-router.");                
            }
        }
         
        String path = null;
        if (port instanceof MaterializedRouterPortConfig) {            
            path = pathManager.getPortRoutesPath(route.nextHopPort, id);
        } else {
            path = pathManager.getRouterRoutesPath(routerId, id);
        }
        JSONSerializer<Route> serializer = new JSONSerializer<Route>();
        ops.add(Op.create(path, serializer.objToBytes(route), 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Add reference to this        
        RouteRefConfig routeRef = new RouteRefConfig(path);
        JSONSerializer<RouteRefConfig> refSerializer = 
            new JSONSerializer<RouteRefConfig>();
        ops.add(Op.create(pathManager.getRoutePath(id), 
                refSerializer.objToBytes(routeRef),
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        zk.multi(ops);
    }
    
/*    public Route get(UUID id) {
        byte[] data = zk.getData(pathManager.getRoutePath(id), null, null);
        
        return RouterDirectory.bytesToRouter(data);  
    }*/
    
}
