/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.UUID;

import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.openflow.Controller;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.state.PortLocationMap;

public abstract class AbstractController implements Controller {

    Logger log = LoggerFactory.getLogger(AbstractController.class);

    protected int datapathId;

    protected ControllerStub controllerStub;

    protected HashMap<UUID, Integer> portUuidToNumberMap;
    protected HashMap<Integer, UUID> portNumToUuid;

    // Tunnel management data structures
    protected HashMap<Integer, InetAddress> tunnelPortNumToPeerIp;

    private OpenvSwitchDatabaseConnection ovsdb;

    protected int greKey;

    public final short nonePort = OFPort.OFPP_NONE.getValue();

    public AbstractController(
            int datapathId,
            UUID switchUuid,
            int greKey,
            OpenvSwitchDatabaseConnection ovsdb,
            PortLocationMap dict,  /* FIXME(jlm): Replace with PortToIntMap, 
			use addWatcher for port_location_update() callback */
            long flowExpireMinMillis,
            long flowExpireMaxMillis,
            long idleFlowExpireMillis,
            InetAddress internalIp) {
        this.datapathId = datapathId;
        this.ovsdb = ovsdb;
        this.greKey = greKey;
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
        // TODO: Maybe find and record the datapath_id?
	//	 The python implementation did, but here we get the dp_id
	//	 in the constructor.

        // Delete all currently installed flows.
        OFMatch match = new OFMatch();
        controllerStub.sendFlowModDelete(match, false, (short)0, nonePort);

        // Add all the ports.
        for (OFPhysicalPort portDesc : controllerStub.getFeatures().getPorts())
            addPort(portDesc, portDesc.getPortNumber());
    }

    @Override
    public void onConnectionLost() {
        clear();
        portNumToUuid.clear();
        tunnelPortNumToPeerIp.clear();
    }

    @Override
    public abstract void onPacketIn(int bufferId, int totalLen, short inPort,
                                    byte[] data);

    @Override
    public final void onPortStatus(OFPhysicalPort portDesc,
                                   OFPortReason reason) {
        if (reason == OFPortReason.OFPPR_ADD) {
            short portNum = portDesc.getPortNumber();
            addPort(portDesc, portNum);

            UUID uuid = getPortUuidFromOvsdb(datapathId, portNum);
            if (uuid != null)
                portNumToUuid.put(new Integer(portNum), uuid);

            if (isGREPortOfKey(portDesc.getName())) {
                InetAddress peerIp = peerIpOfGrePortName(portDesc.getName());
                // TODO: Error out if already tunneled to this peer.
                tunnelPortNumToPeerIp.put(new Integer(portNum), peerIp);
            }
        } else if (reason == OFPortReason.OFPPR_DELETE) {
            deletePort(portDesc);
	    Integer portNum = new Integer(portDesc.getPortNumber());
	    portNumToUuid.remove(portNum);
	    tunnelPortNumToPeerIp.remove(portNum);
        } else {
            modifyPort(portDesc);
            UUID uuid = getPortUuidFromOvsdb(datapathId, 
				             portDesc.getPortNumber());
            Integer portNum = new Integer(portDesc.getPortNumber());
            if (uuid != null)
                portNumToUuid.put(portNum, uuid);
            else
                portNumToUuid.remove(portNum);

            if (isGREPortOfKey(portDesc.getName())) {
                InetAddress peerIp = peerIpOfGrePortName(portDesc.getName());
	 	tunnelPortNumToPeerIp.put(portNum, peerIp);
            } else {
		tunnelPortNumToPeerIp.remove(portNum);
 	    }
        }
    }

    protected abstract void addPort(OFPhysicalPort portDesc, short portNum);
    protected abstract void deletePort(OFPhysicalPort portDesc);
    protected abstract void modifyPort(OFPhysicalPort portDesc);

    @Override
    public abstract void onFlowRemoved(OFMatch match, long cookie,
            short priority, OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount);

    @Override
    public void onMessage(OFMessage m) {
        log.debug("onMessage: {}", m);
        // Don't do anything else.
    }

    /* Clean up resources, especially the ZooKeeper state. */
    abstract public void clear();

    /* Maps a port UUID to its number on the local datapath. */
    public int portUuidToNumber(UUID port_uuid) {
        return portUuidToNumberMap.get(port_uuid);
    }

    protected InetAddress peerOfTunnelPortNum(int portNum) {
        return tunnelPortNumToPeerIp.get(portNum);
    }

    protected boolean isTunnelPortNum(int portNum) {
        return tunnelPortNumToPeerIp.containsKey(new Integer(portNum));
    }

    private boolean isGREPortOfKey(String portName) {
        if (portName == null || portName.length() != 15)
            return false;
        String greString = String.format("tn%05x", greKey);
        return portName.startsWith(greString);
    }

    protected InetAddress peerIpOfGrePortName(String portName) {
        String hexAddress = portName.substring(7, 15);
        Integer intAddress = Integer.parseInt(hexAddress, 16); 
        byte[] byteAddress = { (byte) (intAddress >> 24),
                               (byte) ((intAddress >> 16)&0xff),
                               (byte) ((intAddress >> 8)&0xff),
                               (byte) (intAddress&0xff)
                             };
        try {
            return InetAddress.getByAddress(byteAddress);
        } catch (UnknownHostException e) {
            throw new RuntimeException("getByAddress on a raw address threw " +
                                       "an UnknownHostException", e);
        }
        // FIXME: Test this!
    }

    protected UUID getPortUuidFromOvsdb(int datapathId, short portNum) {
        String extId = ovsdb.getPortExternalId(datapathId, portNum, "midonet");
        if (extId == null)
            return null;
        return UUID.fromString(extId);
    }
}
