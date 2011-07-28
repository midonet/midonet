/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.UUID;

import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.state.ReplicatedMap;
import com.midokura.midolman.state.PortLocationMap;
import com.midokura.midolman.state.MacPortMap;


public class BridgeController extends AbstractController {

    Logger log = LoggerFactory.getLogger(BridgeController.class);

    PortLocationMap port_locs;
    MacPortMap mac_to_port;
    long mac_port_timeout;
    private final int nonePort = OFPort.OFPP_NONE.getValue();

    // The delayed deletes for mac_to_port.
    HashMap<byte[], UUID> delayedDeletes;

    HashMap<MacPort, Integer> flowCount;

    HashMap<Integer, UUID> portNumToUuid;

    BridgeControllerWatcher macToPortWatcher;

    private class MacPort {
        // Pair<Mac, Port>
        public UUID port;
        public byte[] mac;
    }

    private class BridgeControllerWatcher implements
            ReplicatedMap.Watcher<byte[], UUID> {
        public void processChange(byte[] key, UUID old_uuid, UUID new_uuid) {
            /* Update callback for the MacPortMap */
            
            /* If the new port is local, the flow updates have already been
             * applied, and we return immediately. */
            if (port_is_local(new_uuid))
                return;
            log.debug("BridgeControllerWatcher.processChange: mac " + 
                      macAsAscii(key) + " changed from port " + 
                      old_uuid.toString() + " to port " + new_uuid.toString());

            /* If the MAC's old port was local, we need to invalidate its
             * flows. */
            if (port_is_local(old_uuid)) {
                log.debug("BridgeControllerWatcher.processChange: Old port " +
                          "was local.  Invalidating its flows.");
                invalidateFlowsFromMac(key);
            }

            invalidateFlowsToMac(key);
        }
    }

    public BridgeController(int datapathId, UUID switchUuid, int greKey,
            PortLocationMap port_loc_map, MacPortMap mac_port_map,
            long flowExpireMinMillis, long flowExpireMaxMillis,
            long idleFlowExpireMillis, InetAddress publicIp,
            long macPortTimeoutMillis) {
        super(datapathId, switchUuid, greKey, port_loc_map, flowExpireMinMillis,
              flowExpireMaxMillis, idleFlowExpireMillis, publicIp);
        mac_to_port = mac_port_map;
        mac_port_timeout = macPortTimeoutMillis;
        port_locs = port_loc_map;
        delayedDeletes = new HashMap<byte[], UUID>();
        flowCount = new HashMap<MacPort, Integer>();
        portNumToUuid = new HashMap<Integer, UUID>();
        macToPortWatcher = new BridgeControllerWatcher();
        mac_to_port.addWatcher(macToPortWatcher);
    }

    @Override
    public void clear() {
        port_locs.stop();
        mac_to_port.stop();
        mac_to_port.removeWatcher(macToPortWatcher);
        // FIXME(jlm): remove all mac->port entries
        // FIXME(jlm): Clear all flows.
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort,
            byte[] data) {
        // TODO Auto-generated method stub
    }

    private void invalidateFlowsFromMac(byte[] mac) {
        log.debug("BridgeController: invalidating flows with dl_src " +
		  macAsAscii(mac));
	OFMatch match = new MidoMatch();
        match.setDataLayerSource(mac);
        sendFlowModDelete(false, match, 0, nonePort);
    }

    private void invalidateFlowsToMac(byte[] mac) {
        log.debug("BridgeController: invalidating flows with dl_dst " +
		  macAsAscii(mac));
	OFMatch match = new MidoMatch();
        match.setDataLayerDestination(mac);
        sendFlowModDelete(false, match, 0, nonePort);
    }

    private boolean port_is_local(UUID port) {
        return portUuidToNumberMap.containsKey(port);
    }

    static public String macAsAscii(byte[] mac) {
        // FIXME: Move to packet/ somewhere.
        assert mac.length == 6;
        String rv = String.format("%02x:%02x:%02x:%02x:%02x:%02x",
	                          mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]
                                 );
        return rv;
    }

    public void sendFlowModDelete(boolean strict, OFMatch match, int priority,
                                  int outPort) { 
	// FIXME
    }

    @Override
    public void onPortStatus(OFPhysicalPort port, OFPortReason reason) {
        if (reason == OFPortReason.OFPPR_ADD)
            addPort(port);
        else if (reason == OFPortReason.OFPPR_DELETE)
            deletePort(port);
        else {
            /* Port modified. */
            /* TODO: Handle this. */
        }
    }

    private void addPort(OFPhysicalPort portDesc) {
        int portNum = portDesc.getPortNumber();
        if (isTunnelPortNum(portNum))
            invalidateFlowsToPeer(peerOfTunnelPortNum(portNum));
        else {
            UUID portUuid = portNumToUuid.get(portNum);
            if (portUuid != null)
                invalidateFlowsToPortUuid(portUuid);
        }

        UUID uuid = getPortUuidFromOvsdb(datapathId, portNum);
        if (uuid != null)
            portNumToUuid.put(portNum, uuid);
    }

    private void deletePort(OFPhysicalPort portDesc) {
        // FIXME
    }

    private void invalidateFlowsToPortUuid(UUID port_uuid) {
        // FIXME
    }

    private void invalidateFlowsToPeer(InetAddress peer_ip) {
        // FIXME
    }

    private InetAddress peerOfTunnelPortNum(int portNum) {
        try {
            return InetAddress.getByName("127.0.0.1");  // FIXME
        } catch (java.net.UnknownHostException e) {
            throw new RuntimeException("InetAddress can't handle 127.0.0.1");
        }
    }

    private boolean isTunnelPortNum(int portNum) {
        return false;  // FIXME
    }

    private UUID getPortUuidFromOvsdb(int datapathId, int portNum) {
        return new UUID(0, 0);  // FIXME
    }
}
