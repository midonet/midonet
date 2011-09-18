/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman;

import java.io.IOException;
import java.net.InetAddress;
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
import com.midokura.midolman.state.MacPortMap;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.RouterDirectory;
import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.MemcacheCache;
import com.midokura.midolman.util.Net;

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
        
        midolmanConfig = config.configurationAt("midolman");

        String portRoot = midolmanConfig.getString("ports_subdirectory");
        this.portDirectory = new PortDirectory(directory.getSubDirectory(portRoot));
        
        String bridgeRoot = midolmanConfig.getString("bridges_subdirectory");
        this.bridgeDirectory = new DeviceToGreKeyMap(directory.getSubDirectory(bridgeRoot));
        
        String routerRoot = midolmanConfig.getString("routers_subdirectory");
        this.routerDirectory = new RouterDirectory(directory.getSubDirectory(routerRoot));
    }

    @Override
    public void setControllerStub(ControllerStub controllerStub) {
        this.controllerStub = controllerStub;
    }

    @Override
    public void onConnectionMade() {
        log.info("onConnectionMade");

        try {
            long datapathId = controllerStub.getFeatures().getDatapathId();

            // lookup midolman-vnet of datapath
            String uuid = ovsdb.getDatapathExternalId(datapathId, midolmanConfig.getString("midolman_ext_id_key", "midolman-vnet"));
            
            if (uuid == null) {
                log.warn("onConnectionMade: datapath {} connected but has no relevant external id, ignore it", datapathId);
                return;
            }
            
            UUID deviceId = UUID.fromString(uuid);
            
            // TODO: is this the right way to check that a DP is for a VRN?
            // ----- No.  We should have a directory of VRN UUIDs in ZooKeeper,
            //       just like for Bridges and Routers.
            if (uuid.equals(config.configurationAt("vrn")
                                  .getString("router_network_id"))) {
                Directory portLocationDirectory = 
                    directory.getSubDirectory(
                        midolmanConfig.getString("port_locations_subdirectory"))
                    .getSubDirectory("/" + uuid);

                PortToIntNwAddrMap portLocationMap =
                        new PortToIntNwAddrMap(portLocationDirectory);
                
                long idleFlowExpireMillis = 
                         config.configurationAt("openflow")
                               .getLong("flow_idle_expire_millis");
                InetAddress localNwAddr = InetAddress.getByName(
                        config.configurationAt("openflow")
                            .getString("public_ip_address"));

                String memcacheHosts = config.configurationAt("memcache")
                                             .getString("memcache_hosts");
                
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
                
                newController.onConnectionMade();
            } else if (bridgeDirectory.exists(deviceId)) {
                log.info("Creating Bridge {}", uuid);

                Directory portLocationDirectory = 
                    directory.getSubDirectory(
                        midolmanConfig.getString("port_locations_subdirectory"))
                    .getSubDirectory("/" + uuid);

                PortToIntNwAddrMap portLocationMap =
                        new PortToIntNwAddrMap(portLocationDirectory);

                Directory macPortDir = 
                    directory.getSubDirectory(
                        midolmanConfig.getString("mac_port_subdirectory", 
                                                 "/mac_port"));
                MacPortMap macPortMap = new MacPortMap(macPortDir);

                long idleFlowExpireMillis = 
                         config.configurationAt("openflow")
                               .getLong("flow_idle_expire_millis");
                long flowExpireMillis = 
                         config.configurationAt("openflow")
                               .getLong("flow_expire_millis");
                long macPortTimeoutMillis = 
                         config.configurationAt("bridge")
                               .getLong("mac_port_mapping_expire_millis");
                int localNwAddr = config.configurationAt("openflow")
                                        .getInt("public_ip_address");

                Controller newController = new BridgeController(
                        datapathId,
                        deviceId,
                        bridgeDirectory.getGreKey(deviceId),
                        portLocationMap,
                        macPortMap,
                        flowExpireMillis,
                        idleFlowExpireMillis,
                        Net.convertIntToInetAddress(localNwAddr),
                        macPortTimeoutMillis,
                        ovsdb,
                        reactor);
                
                controllerStub.setController(newController);
                controllerStub = null;
            } else {
                log.info("can't handle this datapath, disconnecting");
                controllerStub.close();
            }
            
        } catch (KeeperException e) {
            log.warn("ZK error", e);
        } catch (IOException e) {
            log.warn("IO error", e);
        } catch (InterruptedException e) {
            log.warn("device construction interrupted", e);
        }
    }

    @Override
    public void onConnectionLost() {
        log.info("onConnectionLost");
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort, byte[] data) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds, int durationNanoseconds,
            short idleTimeout, long packetCount, long byteCount) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void onPortStatus(OFPhysicalPort port, OFPortReason status) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void onMessage(OFMessage m) {
        throw new UnsupportedOperationException();
    }

}
