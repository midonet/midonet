/*
 * @(#)PortZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.state.PortDirectory.BridgePortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.PortConfig;
import com.midokura.midolman.state.PortDirectory.RouterPortConfig;

/**
 * This class was created to handle multiple ops feature in Zookeeper.
 * 
 * @version        1.6 08 Sept 2011
 * @author         Ryu Ishimoto
 */
public class PortZkManager {
    
    private ZkPathManager pathManager = null;
    private ZooKeeper zk = null;

    /**
     * Default constructor.
     * 
     * @param zk Zookeeper object.
     * @param basePath  Directory to set as the base.
     */
    public PortZkManager(ZooKeeper zk, String basePath) {
        this.pathManager = new ZkPathManager(basePath);
        this.zk = zk;
    }
    
    /**
     * Add a new port.
     * @param id  Port UUID.
     * @param port  PortConfig object.
     * @throws IOException  Error serializing.
     * @throws KeeperException   Zookeeper error.
     * @throws InterruptedException  Thread paused too long.
     */
    public void create(UUID id, PortConfig port) 
            throws IOException, KeeperException, InterruptedException {
        if (!(port instanceof BridgePortConfig
                || port instanceof LogicalRouterPortConfig 
                || port instanceof MaterializedRouterPortConfig))
            throw new IllegalArgumentException("Unrecognized port type.");

        byte[] data = PortDirectory.portToBytes(port);
        List<Op> ops = new ArrayList<Op>();

        // Create /ports/<portId>
        ops.add(Op.create(pathManager.getPortPath(id), data, 
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        if (port instanceof RouterPortConfig) {
            // Create /routers/<routerId>/ports/<portId>
            ops.add(Op.create(
                        pathManager.getRouterPortPath(port.device_id, id),
                        null, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
            
            // Create /ports/<portId>/routes
           ops.add(Op.create(pathManager.getPortRoutesPath(id), null,
                   Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
           for (Route rt : ((RouterPortConfig) port).routes) {
               // Create /ports<portId>/routes/<route_value>
               ops.add(Op.create(pathManager.getPortRoutesPath(id, rt), null,
                       Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
           }
        }
        this.zk.multi(ops);        
    }
}
