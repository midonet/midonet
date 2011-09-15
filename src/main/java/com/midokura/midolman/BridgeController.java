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
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.state.ReplicatedMap;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.MacPortMap;


public class BridgeController extends AbstractController {

    Logger log = LoggerFactory.getLogger(BridgeController.class);

    PortToIntNwAddrMap port_locs;
    MacPortMap mac_to_port;
    long mac_port_timeout;

    // The delayed deletes for mac_to_port.
    HashMap<byte[], UUID> delayedDeletes;

    HashMap<MacPort, Integer> flowCount;

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
            PortToIntNwAddrMap port_loc_map, MacPortMap mac_port_map,
            long flowExpireMinMillis, long flowExpireMaxMillis,
            long idleFlowExpireMillis, InetAddress publicIp,
            long macPortTimeoutMillis, OpenvSwitchDatabaseConnection ovsdb) {
        super(datapathId, switchUuid, greKey, ovsdb, port_loc_map,
	      flowExpireMinMillis, flowExpireMaxMillis, idleFlowExpireMillis,
              publicIp);
        mac_to_port = mac_port_map;
        mac_port_timeout = macPortTimeoutMillis;
        port_locs = port_loc_map;
        delayedDeletes = new HashMap<byte[], UUID>();
        flowCount = new HashMap<MacPort, Integer>();
        macToPortWatcher = new BridgeControllerWatcher();
        mac_to_port.addWatcher(macToPortWatcher);
    }

    @Override
    public void clear() {
        port_locs.stop();
        mac_to_port.stop();
        // .stop() includes a .clear() of the underlying map, so we don't
        // need to clear out the entries here.
        mac_to_port.removeWatcher(macToPortWatcher);
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
        controllerStub.sendFlowModDelete(match, false, (short)0, nonePort);
    }

    private void invalidateFlowsToMac(byte[] mac) {
        log.debug("BridgeController: invalidating flows with dl_dst " +
                  macAsAscii(mac));
        OFMatch match = new MidoMatch();
        match.setDataLayerDestination(mac);
        controllerStub.sendFlowModDelete(match, false, (short)0, nonePort);
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

    protected void addPort(OFPhysicalPort portDesc, short portNum) {
        if (isTunnelPortNum(portNum))
            invalidateFlowsToPeer(peerOfTunnelPortNum(portNum));
        else {
            UUID portUuid = portNumToUuid.get(portNum);
            if (portUuid != null)
                invalidateFlowsToPortUuid(portUuid);
        }
    }

    protected void deletePort(OFPhysicalPort portDesc) {
        // FIXME
    }

    protected void modifyPort(OFPhysicalPort portDesc) {
        // FIXME
    }

    private void invalidateFlowsToPortUuid(UUID port_uuid) {
        // FIXME
    }

    private void invalidateFlowsToPeer(InetAddress peer_ip) {
        // FIXME
    }
}
