/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman;

import java.io.IOException;
import java.util.UUID;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.layer3.NetworkController;
import com.midokura.midolman.openflow.Controller;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.state.DeviceToGreKeyMap;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.RouterDirectory;
import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.MemcacheCache;

public class ControllerTrampoline implements Controller {

    private static final Logger log = LoggerFactory.getLogger(ControllerTrampoline.class);

    private HierarchicalConfiguration config;
    private Configuration midolmanConfig;
    private OpenvSwitchDatabaseConnection ovsdb;
    private Directory directory;
    private Reactor reactor;
    
    private PortDirectory portDirectory;
    private DeviceToGreKeyMap bridgeDirectory;
    private RouterDirectory routerDirectory;

    private ControllerStub controllerStub;

    /* directory is the "midonet root", not zkConnection.getRootDirectory() */
    public ControllerTrampoline(
            HierarchicalConfiguration config,
            OpenvSwitchDatabaseConnection ovsdb,
            Directory directory,
            Reactor reactor) throws KeeperException {

        this.config = config;
        this.ovsdb = ovsdb;
        this.directory = directory;
        this.reactor = reactor;
        
        Configuration midoConfig = config.configurationAt("midolman");

        String portRoot = midoConfig.getString("ports_subdirectory", "ports");
        this.portDirectory = new PortDirectory(directory.getSubDirectory(portRoot));
        
        String bridgeRoot = midoConfig.getString("bridges_subdirectory", "bridges");
        this.bridgeDirectory = new DeviceToGreKeyMap(directory.getSubDirectory(bridgeRoot));
        
        String routerRoot = midoConfig.getString("routers_subdirectory", "routers");
        this.routerDirectory = new RouterDirectory(directory.getSubDirectory(routerRoot));
    }

    @Override
    public void setControllerStub(ControllerStub controllerStub) {
        this.controllerStub = controllerStub;
    }

    @Override
    public void onConnectionMade() {
        try {
            long datapathId = controllerStub.getFeatures().getDatapathId();

            // lookup midolman-vnet of datapath
            String uuid = ovsdb.getDatapathExternalId(datapathId, midolmanConfig.getString("midolman_ext_id_key", "midolman-vnet"));
            
            if (uuid == null) {
                log.warn("onConnectionMade: datapath {} connected but has no relevant external id, ignore it", datapathId);
                return;
            }
            
            UUID deviceId = UUID.fromString(uuid);
            
            //TODO: is this the right way to check that a DP is for a VRN?
            if(deviceId.equals(config.configurationAt("vrn").getString("router_network_id"))) {
                
                Directory portLocationDirectory = directory.getSubDirectory(midolmanConfig.getString("port_location_dicts_root_key")).getSubDirectory(uuid);

                PortToIntNwAddrMap portLocationMap =
                        new PortToIntNwAddrMap(portLocationDirectory);
                
                long idleFlowExpireMillis = config.configurationAt("openflow").getLong("flow_idle_expire");
                int localNwAddr = config.configurationAt("openflow").getInt("public_ip_address");

                String memcacheHosts = config.configurationAt("memcache").getString("memcache_hosts");
                
                Cache cache = new MemcacheCache(memcacheHosts, 3);
                
                Controller newController = new NetworkController(
                        datapathId,
                        deviceId,
                        config.configurationAt("vrn").getInt("router_network_gre_key"),
                        portLocationMap,
                        idleFlowExpireMillis,
                        localNwAddr,
                        routerDirectory,
                        portDirectory,
                        ovsdb,
                        reactor,
                        cache);
                
                controllerStub.setController(newController);
                controllerStub = null;
            }
            
        } catch (KeeperException e) {
            log.warn("ZK error", e);
        } catch (IOException e) {
            log.warn("IO error", e);
        }
    }

    @Override
    public void onConnectionLost() {
        // TODO Auto-generated method stub

    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort, byte[] data) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds, int durationNanoseconds,
            short idleTimeout, long packetCount, long byteCount) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onPortStatus(OFPhysicalPort port, OFPortReason status) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onMessage(OFMessage m) {
        // TODO Auto-generated method stub

    }

}
