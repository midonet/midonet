/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman;

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

public class ControllerTrampoline implements Controller {

    private static final Logger log = LoggerFactory.getLogger(ControllerTrampoline.class);

    private OpenvSwitchDatabaseConnection ovsdb;
    
    private ControllerStub controllerStub;

    public ControllerTrampoline(OpenvSwitchDatabaseConnection ovsdb) {
        this.ovsdb = ovsdb;
    }

    @Override
    public void setControllerStub(ControllerStub controllerStub) {
        this.controllerStub = controllerStub;
    }

    @Override
    public void onConnectionMade() {
        // TODO Auto-generated method stub
        long datapathId = controllerStub.getFeatures().getDatapathId();

        // TODO: lookup midolman-vnet of datapath

        Controller newController = null;

        controllerStub.setController(newController);
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
