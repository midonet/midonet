/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.state.MacPortMap;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.ReplicatedMap;
import com.midokura.midolman.util.Net;


public class BridgeController extends AbstractController {

    Logger log = LoggerFactory.getLogger(BridgeController.class);

    MacPortMap macPortMap;
    long mac_port_timeout;
    Reactor reactor;
    short flowExpireSeconds, idleFlowExpireSeconds;
    static final short flowPriority = 1000;    // TODO: Make configurable.

    static private class PortFuture {
        // Pair<Port, Future>
        public UUID port;
        public Future future;
        public PortFuture(UUID p, Future f) { port = p; future = f; }
    }
    // The delayed deletes for macPortMap.
    HashMap<MAC, PortFuture> delayedDeletes;

    HashMap<MacPort, Integer> flowCount;

    BridgeControllerWatcher macToPortWatcher;

    static private class MacPort {
        // Pair<Mac, Port>
        public MAC mac;
        public UUID port;

        public MacPort(MAC addr, UUID uuid) {
            mac = addr;
            port = uuid;
        }

        public boolean equals(Object rhs) {
            if (this == rhs)
                return true;
            if (null == rhs)
                return false;
            if (!(rhs instanceof MacPort))
                return false;

            return mac.equals(((MacPort)rhs).mac) &&
                   port.equals(((MacPort)rhs).port);
        }

        public int hashCode() {
            return mac.hashCode() ^ port.hashCode();
        }
    }

    private class BridgeControllerWatcher implements
            ReplicatedMap.Watcher<MAC, UUID> {
        public void processChange(MAC key, UUID old_uuid, UUID new_uuid) {
            /* Update callback for the MacPortMap */

            /* If the new port is local, the flow updates have already been
             * applied, and we return immediately. */
            if (port_is_local(new_uuid)) {
                log.info("BridgeControllerWatcher.processChange: port {} is " +
                         "local, returning without taking action", new_uuid);
                return;
            }
            log.info("BridgeControllerWatcher.processChange: mac {} changed "
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
            IntIPv4 publicIp, long macPortTimeoutMillis,
            OpenvSwitchDatabaseConnection ovsdb, Reactor reactor,
            String externalIdKey) {
        super(datapathId, switchUuid, greKey, ovsdb, port_loc_map,
              publicIp, externalIdKey);
        macPortMap = mac_port_map;
        mac_port_timeout = macPortTimeoutMillis;
        delayedDeletes = new HashMap<MAC, PortFuture>();
        flowCount = new HashMap<MacPort, Integer>();
        macToPortWatcher = new BridgeControllerWatcher();
        macPortMap.addWatcher(macToPortWatcher);
        this.reactor = reactor;
        idleFlowExpireSeconds = (short) (idleFlowExpireMillis / 1000);
        flowExpireSeconds = (short) (flowExpireMillis / 1000);
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount) {
        log.debug("onFlowRemoved: {} {} {} {}",
                new Object[] {match, reason, durationSeconds, packetCount});
        if ((match.getWildcards() & OFMatch.OFPFW_IN_PORT) != 0) {
            log.error("onFlowRemoved flow IN_PORT wildcarded");
            return;
        }
        if ((match.getWildcards() & OFMatch.OFPFW_DL_SRC) != 0) {
            log.error("onFlowRemoved flow DL_SRC wildcarded");
            return;
        }

        UUID inPortUuid = portNumToUuid.get(new Integer(match.getInputPort()));
        if (inPortUuid == null) {
            log.info("onFlowRemoved port has no UUID");
            return;
        }

        // Decrement ref count
        MacPort flowcountKey = new MacPort(new MAC(match.getDataLayerSource()),
                                           inPortUuid);
        Integer count = flowCount.get(flowcountKey);
        if (count == null) {
            log.info("onFlowRemoved MAC {} port#{} id:{} but no flowCount",
                     new Object[] { flowcountKey.mac, match.getInputPort(),
                                    inPortUuid });
        } else if (count > 1) {
            flowCount.put(flowcountKey, new Integer(count-1));
            log.info("onFlowRemoved decreased flow count for srcMac {} from " +
                     "port#{} id:{} to {}", new Object[] { flowcountKey.mac,
                     match.getInputPort(), inPortUuid, count-1 });
        } else {
            log.info("onFlowRemoved last flow for srcMac {} on port #{} id:{}"+
                     " removed at {}", new Object[] { flowcountKey.mac,
                     match.getInputPort(), inPortUuid, new Date() });
            flowCount.remove(flowcountKey);
            expireMacPortEntry(flowcountKey.mac, inPortUuid, false);
        }
    }

    private void expireMacPortEntry(final MAC mac, UUID port, boolean delete) {
        UUID currentPort = macPortMap.get(mac);
        if (currentPort == null) {
            log.debug("expireMacPortEntry: MAC->port mapping for MAC {} " +
                      "has already been removed", mac);
        } else if (currentPort.equals(port)) {
            if (delete) {
                log.info("expireMacPortEntry: deleting the mapping from " +
                          "MAC {} to port {}", mac, port);
                // Remove & cancel any delayedDeletes invalidated.
                PortFuture delayedDelete = delayedDeletes.remove(mac);
                if (delayedDelete != null)
                    delayedDelete.future.cancel(false);
                // The macPortMap watcher will invalidate flows associated
                // with this MAC, so we don't need to do it here.
                try {
                    macPortMap.remove(mac);
                } catch (KeeperException e) {
                    log.error("Delete of MAC {} threw ZooKeeper exception {}",
                              mac, e);
                    // TODO: What do we do about this?
                } catch (InterruptedException e) {
                    log.error("Delete of MAC {} interrupted: {}", mac, e);
                    // TODO: What do we do about this?
                }
            } else {
                log.info("expireMacPortEntry: setting the mapping from " +
                         "MAC {} to port {} to expire", mac, port);
                Future future = reactor.schedule(
                    new Runnable() {
                        public void run() {
                            try {
                                // TODO: Should we check that
                                // macPortMap.get(mac) still points to the port?
                                macPortMap.remove(mac);
                            } catch (KeeperException e) {
                                log.error("Delayed delete of MAC {} from " +
                                          "expireMacPortEntry threw ZooKeeper "+
                                          "exception {}", mac, e);
                                // TODO: What do we do about this?
                            } catch (InterruptedException e) {
                                log.error("Delayed delete of MAC {} from " +
                                          "expireMacPortEntry was " +
                                          "interrupted: {}", mac, e);
                                // TODO: What do we do about this?
                            }
                        }
                    }, mac_port_timeout, TimeUnit.MILLISECONDS);
                delayedDeletes.put(mac, new PortFuture(port, future));
            }
        } else {
            log.debug("expireMacPortEntry: MAC {} is now mapped to port {} " +
                      "not {}", new Object[] { mac, currentPort, port });
        }
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort,
                           byte[] data) {
        log.debug("onPacketIn: bufferId {} totalLen {} inPort {}",
                new Object[] {bufferId, totalLen, inPort});

        Ethernet capturedPacket = new Ethernet();
        capturedPacket.deserialize(data, 0, data.length);
        MAC srcDlAddress = capturedPacket.getSourceMACAddress();
        MAC dstDlAddress = capturedPacket.getDestinationMACAddress();
        log.info("Packet recv'd on port {} destination {}",
                 inPort, dstDlAddress);
        UUID outPort = macPortMap.get(dstDlAddress);
        log.info(outPort == null ? "no port found for MAC"
                                 : "MAC is on port " + outPort.toString());
        IntIPv4 destIP = null;
        OFAction[] actions;

        if (outPort != null) {
            destIP = portLocMap.get(outPort);
            log.debug("Destination port maps to host {}", destIP);
            if (destIP == null) {
                log.warn("No host found for port {}", outPort);
            }
        }

        boolean srcAddressIsMcast = Ethernet.isMcast(srcDlAddress);
        if (srcAddressIsMcast) {
            // If a multicast source MAC, drop the packet.
            actions = new OFAction[] { };
            log.info("multicast src MAC, dropping packet");
        } else if (Ethernet.isMcast(dstDlAddress) ||
                   outPort == null ||
                   destIP == null ||
                   (destIP.equals(publicIp) &&
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
            log.debug("onPacketIn: outputting to local port {}", outPort);

            // The port is on this local datapath, so send the flow to it
            // directly.
            short localPortNum = portUuidToNumberMap.get(outPort).shortValue();
            actions = new OFAction[] { new OFActionOutput(localPortNum,
                                                          (short)0) };
        } else {
            // The virtual port is part of a remote datapath.  Tunnel the
            // packet to it.
            log.info("send flow to peer at {}", destIP);
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
                    output = tunnelPortNumOfPeer(destIP).shortValue();
                    actions = new OFAction[] { new OFActionOutput(output,
                                                                  (short)0) };
                } catch (NullPointerException e) {
                    // Tunnel is down.  Drop.  Don't flood, to avoid the 
                    // packet looping and storming.
                    log.info("tunnel down:  Dropping");
                    actions = new OFAction[] { };
                }
            }
        }

        UUID inPortUuid = portNumToUuid.get(new Integer(inPort));
        UUID mappedPortUuid = macPortMap.get(srcDlAddress);

        if (!srcAddressIsMcast && inPortUuid != null &&
                !inPortUuid.equals(mappedPortUuid)) {
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
        log.info("installing flowmod at {} with actions {} triggered by " +
                 "packet from port#{} id:{}", new Object[] { new Date(),
                 actions, inPort, inPortUuid });

        // If the message didn't come from the tunnel port, learn the MAC
        // source address.  We wait to learn a MAC-port mapping until
        // there's a flow from the MAC because flows are used to
        // reference-count the mapping.
        if (inPortUuid != null && !srcAddressIsMcast)
            increaseMacPortFlowCount(srcDlAddress, inPortUuid);
    }

    private void increaseMacPortFlowCount(MAC macAddr, UUID portUuid) {
        MacPort flowcountKey = new MacPort(macAddr, portUuid);
        Integer count = flowCount.get(flowcountKey);
        if (count == null) {
            count = 1;
            // Remove any delayed delete.
            PortFuture delayedDelete = delayedDeletes.remove(macAddr);
            if (delayedDelete != null)
                delayedDelete.future.cancel(false);
            flowCount.put(flowcountKey, count);
        } else {
            flowCount.put(flowcountKey, new Integer(count+1));
        }

        log.info("Increased flow count for source MAC {} from port {} to {}",
                 new Object[] { macAddr, portUuid, count });
        if (!macPortMap.containsKey(macAddr) ||
                                !macPortMap.get(macAddr).equals(portUuid)) {
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
        log.info("invalidating flows with dl_src {}", mac);
        MidoMatch match = new MidoMatch();
        match.setDataLayerSource(mac);
        controllerStub.sendFlowModDelete(match, false, (short)0, nonePort);
    }

    private void invalidateFlowsToMac(MAC mac) {
        log.info("invalidating flows with dl_dst {}", mac);
        MidoMatch match = new MidoMatch();
        match.setDataLayerDestination(mac);
        controllerStub.sendFlowModDelete(match, false, (short)0, nonePort);
    }

    private boolean port_is_local(UUID port) {
        return portUuidToNumberMap.containsKey(port);
    }

    @Override
    protected void addPort(OFPhysicalPort portDesc, short portNum) {
        log.info("addPort: {} {}", portDesc, portNum);

        if (isTunnelPortNum(portNum))
            invalidateFlowsToPeer(peerOfTunnelPortNum(portNum));
        else {
            UUID portUuid = portNumToUuid.get(portNum);
            if (portUuid != null)
                invalidateFlowsToPortUuid(portUuid);
        }
    }

    @Override
    protected void deletePort(OFPhysicalPort portDesc) {
        short inPortNum = portDesc.getPortNumber();
        UUID inPortUuid = portNumToUuid.get(new Integer(inPortNum));
        IntIPv4 peerIP = peerOfTunnelPortNum(inPortNum);
        log.info("Delete callback for port #{} ({}) => {}",
                 new Object[] { inPortNum, inPortUuid, peerIP });
        if (peerIP != null)
            invalidateFlowsToPeer(peerIP);
        if (inPortUuid == null)
            return;
        log.info("Removing all MAC-port mappings to port {}", inPortUuid);
        List<MAC> macList = macPortMap.getByValue(inPortUuid);
        for (MAC mac : macList) {
            log.info("Removing mapping from MAC {} to port {}", mac,
                      inPortUuid);
            flowCount.remove(new MacPort(mac, inPortUuid));
            invalidateFlowsFromMac(mac);
            invalidateFlowsToMac(mac);
            try {
                macPortMap.remove(mac);
            } catch (KeeperException e) {
                log.error("Caught ZooKeeper excetpion {}", e);
                // TODO: What should we do?
            } catch (InterruptedException e) {
                log.error("ZooKeeper operation interrupted: {}", e);
                // TODO: Is ignoring this OK, because we'll resynch with ZK
                // at the next ZK operation?
            }
        }
    }

    @Override
    protected void portMoved(UUID portUuid, IntIPv4 oldAddr, IntIPv4 newAddr) {
        // Here we don't care whether oldAddr is local, because we would
        // get the OVS notification first.
        log.info("portMoved: ID {} from {} to {}", new Object[] {
                    portUuid, oldAddr, newAddr });

        if (port_is_local(portUuid)) {
            log.info("portMoved: port ID {} is local", portUuid);
            if (!newAddr.equals(publicIp)) {
                // TODO(pino): trigger a more useful action like removing
                // the port from OVSDB and raising an alarm.
                log.error("portMoved: peer at {} claims to own my port {}",
                          newAddr, portUuid);
            }
            // We've already reacted to this change.
            return;
        }

        invalidateFlowsToPortUuid(portUuid);
    }

    private void invalidateFlowsToPortUuid(UUID port_uuid) {
        log.info("Invalidating flows to port ID {}", port_uuid);
        List<MAC> macs = macPortMap.getByValue(port_uuid);
        for (MAC mac : macs)
            invalidateFlowsToMac(mac);
    }

    private void invalidateFlowsToPeer(IntIPv4 peer_ip) {
        List<UUID> remotePorts = portLocMap.getByValue(peer_ip);
        for (UUID port : remotePorts) {
            log.info("Invalidating flows for port {}", port);
            invalidateFlowsToPortUuid(port);
        }
    }

    @Override
    public void onConnectionMade() {
        log.info("onConnectionMade");

        macPortMap.start();

        super.onConnectionMade();
    }

    public void clear() {
        log.info("clear");

        // Entries in macPortMap we own are either live with flows, in which
        // case they're in flowCount; or are expiring, in which case they're
        // in delayedDeletes.
        for (MacPort macPort : flowCount.keySet()) {
            log.info("clear: Deleting MAC-Port entry {} :: {}", macPort.mac,
                     macPort.port);
            expireMacPortEntry(macPort.mac, macPort.port, true);
        }
        flowCount.clear();
        for (Map.Entry<MAC, PortFuture> entry : delayedDeletes.entrySet()) {
            log.info("clear: Deleting MAC-Port entry {} :: {}", entry.getKey(),
                     entry.getValue().port);
            expireMacPortEntry(entry.getKey(), entry.getValue().port, true);
            entry.getValue().future.cancel(false);
        }
        delayedDeletes.clear();

        macPortMap.removeWatcher(macToPortWatcher);
        macPortMap.stop();
        // .stop() includes a .clear() of the underlying map, so we don't
        // need to clear out the entries here.

        // Clear all flows.
        MidoMatch match = new MidoMatch();
        controllerStub.sendFlowModDelete(match, false, (short)0, nonePort);
    }
}
