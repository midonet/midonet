/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.UUID;

import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.openflow.Controller;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.state.PortLocationMap;

public abstract class AbstractController implements Controller {

    Logger log = LoggerFactory.getLogger(AbstractController.class);
    
    protected int datapathId;
    
    protected ControllerStub controllerStub;

    protected HashMap<UUID, Integer> portUuidToNumberMap;
    
    public AbstractController(
            int datapathId,
            UUID switchUuid,
            int greKey,
            //ovsdb_connection_factory,
            PortLocationMap dict,
            long flowExpireMinMillis,
            long flowExpireMaxMillis,
            long idleFlowExpireMillis,
            InetAddress internalIp) {
        this.datapathId = datapathId;
        portUuidToNumberMap = new HashMap<UUID, Integer>();
    }
    
    @Override
    public void setControllerStub(ControllerStub controllerStub) {
        this.controllerStub = controllerStub;
    }

    @Override
    public void onConnectionMade() {
        // TODO Auto-generated method stub

    }

    @Override
    public void onConnectionLost() {
        // TODO Auto-generated method stub

    }

    @Override
    public abstract void onPacketIn(int bufferId, int totalLen, short inPort, byte[] data);

    @Override
    public abstract void onFlowRemoved(OFMatch match, long cookie, short priority, OFFlowRemovedReason reason,
            int durationSeconds, int durationNanoseconds, short idleTimeout, long packetCount, long byteCount);

    @Override
    public void onPortStatus(OFPhysicalPort port, OFPortReason status) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onMessage(OFMessage m) {
        log.debug("onMessage: {}", m);
        // TODO Auto-generated method stub

    }

    /* Clean up resources, especially the ZooKeeper state. */
    abstract public void clear();

    /* Maps a port UUID to its number on the local datapath. */
    public int portUuidToNumber(UUID port_uuid) {
        return portUuidToNumberMap.get(port_uuid);
    }
}
