/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman;

import java.io.IOException;
import java.net.InetAddress;
import java.util.UUID;

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
import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MacPortMap;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.ZkPathManager;
import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.MemcacheCache;
import com.midokura.midolman.util.Net;

public class ControllerTrampoline implements Controller {

    private static final Logger log = LoggerFactory
            .getLogger(ControllerTrampoline.class);

    private HierarchicalConfiguration config;
    private OpenvSwitchDatabaseConnection ovsdb;
    private Directory directory;
    private ZkPathManager pathMgr;
    private Reactor reactor;
    private String externalIdKey;

    private BridgeZkManager bridgeMgr;
    private ControllerStub controllerStub;

    /* directory is the "midonet root", not zkConnection.getRootDirectory() */
    public ControllerTrampoline(HierarchicalConfiguration config,
            OpenvSwitchDatabaseConnection ovsdb, Directory directory,
            Reactor reactor) throws KeeperException {

        this.config = config;
        this.ovsdb = ovsdb;
        this.directory = directory;
        this.reactor = reactor;
        this.pathMgr = new ZkPathManager("");

        externalIdKey = config.configurationAt("openvswitch").getString(
                "midolman_ext_id_key", "midolman-vnet");

        this.bridgeMgr = new BridgeZkManager(directory, "");
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
            String uuid = ovsdb.getDatapathExternalId(datapathId, externalIdKey);
            
            if (uuid == null) {
                log.warn("onConnectionMade: datapath {} connected but has no relevant external id, ignore it", datapathId);
                return;
            }
            
            UUID deviceId = UUID.fromString(uuid);
            
            // TODO: is this the right way to check that a DP is for a VRN?
            // ----- No.  We should have a directory of VRN UUIDs in ZooKeeper,
            //       just like for Bridges and Routers.
            Controller newController;
            if (uuid.equals(config.configurationAt("vrn")
                                  .getString("router_network_id"))) {
                Directory portLocationDirectory = 
                    directory.getSubDirectory(pathMgr.getVRNPortLocationsPath());

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
                
                newController = new NetworkController(
                        datapathId,
                        deviceId,
                        config.configurationAt("vrn").getInt("router_network_gre_key"),
                        portLocationMap,
                        idleFlowExpireMillis,
                        localNwAddr,
                        new PortZkManager(directory, ""),
                        new RouterZkManager(directory, ""),
                        new RouteZkManager(directory, ""),
                        new ChainZkManager(directory, ""),
                        new RuleZkManager(directory, ""),
                        ovsdb,
                        reactor,
                        cache,
                        externalIdKey);
            } 
            else {
                BridgeConfig bridgeConfig;
                try {
                    bridgeConfig = bridgeMgr.get(deviceId).value;
                }
                catch (Exception e) {
                    log.info("can't handle this datapath, disconnecting");
                    controllerStub.close();
                    return;
                }
                log.info("Creating Bridge {}", uuid);

                Directory portLocationDirectory = 
                    directory.getSubDirectory(
                            pathMgr.getBridgePortLocationsPath(deviceId));

                PortToIntNwAddrMap portLocationMap =
                        new PortToIntNwAddrMap(portLocationDirectory);

                Directory macPortDir = 
                    directory.getSubDirectory(
                            pathMgr.getBridgeMacPortsPath(deviceId));
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

                newController = new BridgeController(
                        datapathId,
                        deviceId,
                        bridgeConfig.greKey,
                        portLocationMap,
                        macPortMap,
                        flowExpireMillis,
                        idleFlowExpireMillis,
                        Net.convertIntToInetAddress(localNwAddr),
                        macPortTimeoutMillis,
                        ovsdb,
                        reactor,
                        externalIdKey);
            }
            controllerStub.setController(newController);
            controllerStub = null;
            newController.onConnectionMade();
            
        } catch (KeeperException e) {
            log.warn("ZK error", e);
        } catch (IOException e) {
            log.warn("IO error", e);
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
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount) {
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
