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
    protected HashMap<Integer, UUID> portNumToUuid;

    // Tunnel management data structures
    private HashMap<Integer, InetAddress> tunnelPortNumToPeerIp;

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
        portNumToUuid = new HashMap<Integer, UUID>();
        tunnelPortNumToPeerIp = new HashMap<Integer, InetAddress>();
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
    public abstract void onPacketIn(int bufferId, int totalLen, short inPort,
                                    byte[] data);

    @Override
    public abstract void onFlowRemoved(OFMatch match, long cookie,
            short priority, OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount);

    @Override
    public void onPortStatus(OFPhysicalPort portDesc, OFPortReason reason) {
        if (reason == OFPortReason.OFPPR_ADD) {
            int portNum = portDesc.getPortNumber();
            addPort(portDesc, portNum);

            UUID uuid = getPortUuidFromOvsdb(datapathId, portNum);
            if (uuid != null)
                portNumToUuid.put(portNum, uuid);

            InetAddress peerIp = peerIpOfGrePortName(portDesc.getName());
            if (peerIp != null) {
                // TODO: Error out if already tunneled to this peer.
                tunnelPortNumToPeerIp.put(portNum, peerIp);
            }
        } else if (reason == OFPortReason.OFPPR_DELETE) {
            deletePort(portDesc);
        } else {
            modifyPort(portDesc);
        }
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

    abstract public void sendFlowModDelete(boolean strict, OFMatch match,
 	                                   int priority, int outPort);

    protected InetAddress peerOfTunnelPortNum(int portNum) {
        return tunnelPortNumToPeerIp.get(portNum);
    }

    protected boolean isTunnelPortNum(int portNum) {
        return tunnelPortNumToPeerIp.containsKey(new Integer(portNum));
    }

    protected InetAddress peerIpOfGrePortName(String portName) {
        return null;    // FIXME
    }

    private UUID getPortUuidFromOvsdb(int datapathId, int portNum) {
        return new UUID(0, 0);  // FIXME
        // Should be part of OVS interface.
    }

    protected abstract void deletePort(OFPhysicalPort portDesc);
    protected abstract void modifyPort(OFPhysicalPort portDesc);
    protected abstract void addPort(OFPhysicalPort portDesc, int portNum);
}
