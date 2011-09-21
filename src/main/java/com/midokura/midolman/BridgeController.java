/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman;

import java.net.InetAddress;
import java.util.concurrent.Future;
import java.util.Date;
import java.util.HashMap;
import java.util.UUID;

import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;

import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.state.ReplicatedMap;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.MacPortMap;
import com.midokura.midolman.util.Net;
import com.midokura.midolman.util.MAC;


public class BridgeController extends AbstractController {

    Logger log = LoggerFactory.getLogger(BridgeController.class);

    PortToIntNwAddrMap port_locs;
    MacPortMap macPortMap;
    long mac_port_timeout;
    Reactor reactor;
    short flowExpireSeconds, idleFlowExpireSeconds;
    static final short flowPriority = 1000;    // TODO: Make configurable.

    // The delayed deletes for macPortMap.
    HashMap<MAC, Future> delayedDeletes;

    HashMap<MacPort, Integer> flowCount;

    BridgeControllerWatcher macToPortWatcher;

    private class MacPort {
        // Pair<Mac, Port>
        public MAC mac;
        public UUID port;

        public MacPort(MAC addr, UUID uuid) {
            mac = addr;
            port = uuid;
        }
    }

    private class BridgeControllerWatcher implements
            ReplicatedMap.Watcher<MAC, UUID> {
        public void processChange(MAC key, UUID old_uuid, UUID new_uuid) {
            /* Update callback for the MacPortMap */

            /* If the new port is local, the flow updates have already been
             * applied, and we return immediately. */
            if (port_is_local(new_uuid))
                return;
            log.debug("BridgeControllerWatcher.processChange: mac {} changed "
                      + "from port {} to port {}", new Object[] {
                      key, old_uuid, new_uuid});

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

    public BridgeController(long datapathId, UUID switchUuid, int greKey,
            PortToIntNwAddrMap port_loc_map, MacPortMap mac_port_map,
            long flowExpireMillis, long idleFlowExpireMillis, 
            InetAddress publicIp, long macPortTimeoutMillis, 
            OpenvSwitchDatabaseConnection ovsdb, Reactor reactor,
            String externalIdKey) {
        super(datapathId, switchUuid, greKey, ovsdb, port_loc_map,
              flowExpireMillis, flowExpireMillis, idleFlowExpireMillis,
              publicIp, externalIdKey);
        macPortMap = mac_port_map;
        mac_port_timeout = macPortTimeoutMillis;
        port_locs = port_loc_map;
        delayedDeletes = new HashMap<MAC, Future>();
        flowCount = new HashMap<MacPort, Integer>();
        macToPortWatcher = new BridgeControllerWatcher();
        macPortMap.addWatcher(macToPortWatcher);
        this.reactor = reactor;
        idleFlowExpireSeconds = (short) (idleFlowExpireMillis / 1000);
        flowExpireSeconds = (short) (flowExpireMillis / 1000);
    }

    @Override
    public void clear() {
        port_locs.stop();
        macPortMap.stop();
        // .stop() includes a .clear() of the underlying map, so we don't
        // need to clear out the entries here.
        macPortMap.removeWatcher(macToPortWatcher);
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
        Ethernet capturedPacket = new Ethernet();
        capturedPacket.deserialize(data, 0, data.length);
        MAC srcDlAddress = new MAC(capturedPacket.getSourceMACAddress());
        MAC dstDlAddress = new MAC(capturedPacket.getDestinationMACAddress());
        log.info("Packet recv'd on port {} destination {}",
                 inPort, dstDlAddress);
        UUID outPort = macPortMap.get(dstDlAddress);
        log.info(outPort == null ? "no port found for MAC"
                                 : "MAC is on port " + outPort.toString());
        int destIP = 0;
        OFAction[] actions;

        if (outPort != null) {
            try {
                destIP = portLocMap.get(outPort).intValue();
                log.debug("Destination port maps to host {}",
                          Net.convertIntAddressToString(destIP));
            } catch (NullPointerException e) {
                log.warn("No host found for port {}", outPort);
            }
        }

        boolean srcAddressIsMcast = Ethernet.isMcast(srcDlAddress.address);
        if (srcAddressIsMcast) {
            // If a multicast source MAC, drop the packet.
            actions = new OFAction[] { };
            log.info("multicast src MAC, dropping packet");
        } else if (Ethernet.isMcast(dstDlAddress.address) ||
                   outPort == null ||
                   destIP == 0 ||
                   (destIP == publicIp && 
                        !portUuidToNumberMap.containsKey(outPort))) {
            // Flood if any of:
            //  * multicast destination MAC
            //  * destination port unknown (not in macPortMap)
            //  * destIP (destination peer) unknown (not in portLocMap)
            //  * destIP is local and the output port not in portUuidToNumberMap

            log.info("Flooding from {} port {}",
                     isTunnelPortNum(inPort) ? "tunnel" : "non-tunnel",
                     inPort);
            log.info("outPort {}, destIP {}", outPort, destIP);
            OFPort output = isTunnelPortNum(inPort) ? OFPort.OFPP_FLOOD
                                                    : OFPort.OFPP_ALL;
            actions = new OFAction[] { new OFActionOutput(output.getValue(), 
                                                          (short)0) };
        } else if (portUuidToNumberMap.containsKey(outPort)) {
            // The port is on this local datapath, so send the flow to it 
            // directly.
            short localPortNum = portUuidToNumberMap.get(outPort).shortValue();
            actions = new OFAction[] { new OFActionOutput(localPortNum,
                                                          (short)0) };
        } else {
            // The virtual port is part of a remote datapath.  Tunnel the 
            // packet to it.
            log.info("send flow to peer at {}", 
                      Net.convertIntAddressToString(destIP));
            if (isTunnelPortNum(inPort)) {
                // The packet came in on a tunnel.  Don't send it out the 
                // tunnel to avoid loops.  Just drop.  The flow match will 
                // be invalidated when the destination mac changes port_uuid 
                // or the port_uuid changes location.
                actions = new OFAction[] { };
                log.info("tunnel looping, drop");
            } else {
                short output;
                try {
                    output = peerIpToTunnelPortNum.get(destIP).shortValue();
                } catch (NullPointerException e) {
                    // Tunnel is down.  Flood until the tunnel port comes up.
                    log.info("tunnel down:  Flooding");
                    output = OFPort.OFPP_ALL.getValue();
                }
                actions = new OFAction[] { new OFActionOutput(output,
                                                              (short)0) };
            }
        }

        UUID inPortUuid = portNumToUuid.get(inPort);
        UUID mappedPortUuid = macPortMap.get(srcDlAddress);
        
        if (!srcAddressIsMcast && inPortUuid != null &&
                inPortUuid != mappedPortUuid) {
            // The MAC changed port:  invalidate old flows before installing 
            // a new flowmod for this MAC.
            invalidateFlowsFromMac(srcDlAddress);
            invalidateFlowsToMac(srcDlAddress);
        }

        // Set up a forwarding rule for packets in this flow, and forward 
        // this packet.
        OFMatch match = createMatchFromPacket(capturedPacket, inPort);
        addFlowAndPacketOut(match, 0L, idleFlowExpireSeconds, 
                            flowExpireSeconds, flowPriority,
                            bufferId, true, false, false, actions,
                            inPort, data);
        log.info("installing flowmod at {} with actions {}", new Date(),
                 actions);

        // If the message didn't come from the tunnel port, learn the MAC 
        // source address.  We wait to learn a MAC-port mapping until 
        // there's a flow from the MAC because flows are used to 
        // reference-count the mapping.
        if (inPortUuid != null && !!srcAddressIsMcast)
            increaseMacPortFlowCount(srcDlAddress, inPortUuid);
    }

    private void increaseMacPortFlowCount(MAC macAddr, UUID portUuid) {
        MacPort flowcountKey = new MacPort(macAddr, portUuid);
        Integer count = flowCount.get(flowcountKey);
        if (count == null) {
            count = 1;
            // Remove any delayed delete.
            Future delayedDelete = delayedDeletes.remove(macAddr);
            if (delayedDelete != null)
                delayedDelete.cancel(false);
            flowCount.put(flowcountKey, count);
        } else {
            count++;
        }

        log.debug("Increased flow count for source MAC {} from port {} to {}",
                  new Object[] { macAddr, portUuid, count });
        if (macPortMap.get(macAddr) != portUuid) {
            try {
                macPortMap.put(macAddr, portUuid);
            } catch (KeeperException e) {
                log.error("ZooKeeper threw exception {}", e);
                // TODO: What should we do about this?
            } catch (InterruptedException e) {
                log.error("MacPortMap threw InterruptedException {}", e);
                // TODO: What should we do about this?
            }
        }
    }

    private void invalidateFlowsFromMac(MAC mac) {
        log.debug("BridgeController: invalidating flows with dl_src {}", mac);
        OFMatch match = new MidoMatch();
        match.setDataLayerSource(mac.address);
        controllerStub.sendFlowModDelete(match, false, (short)0, nonePort);
    }

    private void invalidateFlowsToMac(MAC mac) {
        log.debug("BridgeController: invalidating flows with dl_dst {}", mac);
        OFMatch match = new MidoMatch();
        match.setDataLayerDestination(mac.address);
        controllerStub.sendFlowModDelete(match, false, (short)0, nonePort);
    }

    private boolean port_is_local(UUID port) {
        return portUuidToNumberMap.containsKey(port);
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

    private void invalidateFlowsToPeer(Integer peer_ip) {
        // FIXME
    }
}
