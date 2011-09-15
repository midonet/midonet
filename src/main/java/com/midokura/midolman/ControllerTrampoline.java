/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman;

import java.util.UUID;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.openflow.Controller;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.state.DeviceToGreKeyMap;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.RouterDirectory;

public class ControllerTrampoline implements Controller {

    private static final Logger log = LoggerFactory.getLogger(ControllerTrampoline.class);

    private HierarchicalConfiguration config;
    private OpenvSwitchDatabaseConnection ovsdb;
    private Directory directory;
    
    private PortDirectory portDirectory;
    private DeviceToGreKeyMap bridgeDirectory;
    private RouterDirectory routerDirectory;

    private ControllerStub controllerStub;

    public ControllerTrampoline(HierarchicalConfiguration config, OpenvSwitchDatabaseConnection ovsdb, Directory directory) throws Exception {
        this.config = config;
        this.ovsdb = ovsdb;
        this.directory = directory;
        
        String portRoot = config.configurationAt("midolman").getString("ports_root_key", "ports");
        this.portDirectory = new PortDirectory(directory.getSubDirectory(portRoot));
        
        String bridgeRoot = config.configurationAt("midolman").getString("bridges_root_key", "bridges");
        this.bridgeDirectory = new DeviceToGreKeyMap(directory.getSubDirectory(bridgeRoot));
        
        String routerRoot = config.configurationAt("midolman").getString("routers_root_key", "routers");
        this.routerDirectory = new RouterDirectory(directory.getSubDirectory(routerRoot));
    }

    @Override
    public void setControllerStub(ControllerStub controllerStub) {
        this.controllerStub = controllerStub;
    }

    @Override
    public void onConnectionMade() {
        // TODO Auto-generated method stub
        long datapathId = controllerStub.getFeatures().getDatapathId();

        // lookup midolman-vnet of datapath
        String uuid = ovsdb.getDatapathExternalId(datapathId, config.configurationAt("openvswitch").getString("midolman_ext_id_key", "midolman-vnet"));
        
        if (uuid == null) {
            log.warn("onConnectionMade: datapath {} connected but has no relevant external id, ignore it", datapathId);
            return;
        }
        
        UUID bridgeId = UUID.fromString(uuid);
        
//        if (bridgeDirectory.exists(bridgeId)) {
//            
//        }
        
//        Controller newController = null;
//
//        controllerStub.setController(newController);
        controllerStub = null;
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
