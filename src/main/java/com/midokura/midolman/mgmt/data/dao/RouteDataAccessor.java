/*
 * @(#)RouteDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.util.Net;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.ZkConnection;

/**
 * Data access class for routes.
 *
 * @version        1.6 08 Sept 2011
 * @author         Ryu Ishimoto
 */
public class RouteDataAccessor extends DataAccessor {

    /**
     * Default constructor 
     * 
     * @param zkConn Zookeeper connection string
     */
    public RouteDataAccessor(String zkConn) {
        super(zkConn);
    }
 
    private RouteZkManager getRouteZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn);        
        return new RouteZkManager(conn.getZooKeeper(), "/midolman");
    }
    
    private com.midokura.midolman.layer3.Route convertToZkRoute(Route route) {
        NextHop nextHop = null;
        String type = route.getType();
        if (type.equals(Route.Reject)) {
            nextHop = NextHop.REJECT;
        } else if (type.equals(Route.BlackHole)) {
            nextHop = NextHop.BLACKHOLE;
        } else {
            nextHop = NextHop.PORT;
        }
        
        return new com.midokura.midolman.layer3.Route(
                Net.convertAddressToInt(route.getSrcNetworkAddr()),
                route.getSrcNetworkLength(),                
                Net.convertAddressToInt(route.getDstNetworkAddr()),
                route.getDstNetworkLength(),
                nextHop,
                route.getNextHopPort(),
                Net.convertAddressToInt(route.getNextHopGateway()),
                route.getWeight(),
                "some,attr"         // FIXME: Some reason this is required.
                );
    }
    
    /**
     * Add Route object to Zookeeper directories.
     * 
     * @param   route  Route object to add.
     * @throws  Exception  Error adding data to Zookeeper.
     */
    public void create(Route route) throws Exception {
        com.midokura.midolman.layer3.Route rt = convertToZkRoute(route);
        RouteZkManager manager = getRouteZkManager();
        manager.create(route.getId(), route.getRouterId(), rt);
    }
    
    /**
     * Get a Route for the given ID.
     * 
     * @param   id  Route ID to search.
     * @return  Route object with the given ID.
     * @throws  Exception  Error getting data to Zookeeper.
     */
/*    public Route get(UUID id) throws Exception {
        RouteZkManager manager = getRouteZkManager();
        com.midokura.midolman.layer3.Route rt = manager.get(id);
        // TODO: Throw NotFound exception here.
        Port port = convertToPort(config);
        port.setId(id);
        return port;
    } */   
}
