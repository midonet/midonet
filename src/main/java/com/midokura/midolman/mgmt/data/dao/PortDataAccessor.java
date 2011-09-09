/*
 * @(#)PortDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.util.Net;
import com.midokura.midolman.state.BGP;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.PortConfig;

/**
 * Data access class for port.
 *
 * @version        1.6 08 Sept 2011
 * @author         Ryu Ishimoto
 */
public class PortDataAccessor extends DataAccessor {

    /**
     * Default constructor 
     * 
     * @param zkConn Zookeeper connection string
     */
    public PortDataAccessor(String zkConn) {
        super(zkConn);
    }
    
    private PortZkManager getPortZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn);        
        return new PortZkManager(conn.getZooKeeper(), "/midolman");
    } 
    
    private static LogicalRouterPortConfig toLogRouterPortConf(Port port) {                  
        return new LogicalRouterPortConfig(port.getDeviceId(), 
                Net.convertAddressToInt(port.getNetworkAddress()), 
                port.getNetworkLength(),
                Net.convertAddressToInt(port.getPortAddress()),
                new HashSet<Route>(), port.getPeerId());
    }

    private static MaterializedRouterPortConfig toMatRouterPortConf(Port port) {
        return new MaterializedRouterPortConfig(port.getDeviceId(), 
                Net.convertAddressToInt(port.getNetworkAddress()), 
                port.getNetworkLength(),
                Net.convertAddressToInt(port.getPortAddress()),
                new HashSet<Route>(), 
                Net.convertAddressToInt(port.getLocalNetworkAddress()),
                port.getLocalNetworkLength(),
                new HashSet<BGP>());
    }

    private static Port toPort(LogicalRouterPortConfig config) {
        Port port = new Port();
        port.setDeviceId(config.device_id);
        port.setNetworkAddress(Net.convertAddressToString(config.nwAddr));
        port.setNetworkLength(config.nwLength);
        port.setPortAddress(Net.convertAddressToString(config.portAddr));
        port.setPeerId(config.peer_uuid);
        port.setType(Port.LogicalRouterPort);
        return port;
    }
    
    private static Port toPort(MaterializedRouterPortConfig config) {
        Port port = new Port();
        port.setDeviceId(config.device_id);
        port.setNetworkAddress(Net.convertAddressToString(config.nwAddr));
        port.setNetworkLength(config.nwLength);
        port.setPortAddress(Net.convertAddressToString(config.portAddr));
        port.setLocalNetworkAddress(
                Net.convertAddressToString(config.localNwAddr));
        port.setLocalNetworkLength(config.localNwLength);        
        port.setType(Port.MaterializedRouterPort);
        return port;
    }

    private static PortConfig convertToPortConfig(Port port) {
        String type = port.getType();
        if (type.equals(Port.LogicalRouterPort)) {
            return toLogRouterPortConf(port);
        } else if (type.equals(Port.MaterializedRouterPort)) {
            return toMatRouterPortConf(port);
        }
        return null;
    }
    
    private static Port convertToPort(PortConfig config) {
        if(config instanceof LogicalRouterPortConfig) {
            return toPort((LogicalRouterPortConfig) config);
        } else if (config instanceof MaterializedRouterPortConfig) {
            return toPort((MaterializedRouterPortConfig) config);
        }
        return null;
    }
    
    /**
     * Add Port object to Zookeeper directories.
     * 
     * @param   port  Port object to add.
     * @throws  Exception  Error adding data to Zookeeper.
     */
    public void create(Port port) throws Exception {
        PortConfig config = convertToPortConfig(port);
        PortZkManager manager = getPortZkManager();
        manager.create(port.getId(), config);
    }

    /**
     * Update Port entry in ZooKeeper.
     * 
     * @param   port  Port object to update.
     * @throws  Exception  Error adding data to ZooKeeper.
     */
    public void update(UUID id, Port port) throws Exception {
        PortConfig config = convertToPortConfig(port);
        PortZkManager manager = getPortZkManager();
        manager.update(id, config);
    }
    
    /**
     * Get a Port for the given ID.
     * 
     * @param   id  Port ID to search.
     * @return  Port object with the given ID.
     * @throws  Exception  Error getting data to Zookeeper.
     */
    public Port get(UUID id) throws Exception {
        PortZkManager manager = getPortZkManager();
        PortConfig config = manager.get(id);
        // TODO: Throw NotFound exception here.
        Port port = convertToPort(config);
        port.setId(id);
        return port;
    }
    
    /**
     * Get a list of Ports for a router.
     * 
     * @param routerId  UUID of router.
     * @return  A Set of Ports
     * @throws Exception  Zookeeper(or any) error.
     */
     public Port[] list(UUID routerId) throws Exception {
         PortZkManager manager = getPortZkManager();
         List<Port> ports = new ArrayList<Port>();
         HashMap<UUID, PortConfig> configs = manager.list(routerId);
         for (Map.Entry<UUID, PortConfig> entry : configs.entrySet()) {
             Port port = convertToPort(entry.getValue());
             port.setId(entry.getKey());
             ports.add(port);            
         }
        return ports.toArray(new Port[ports.size()]);
    }
}
