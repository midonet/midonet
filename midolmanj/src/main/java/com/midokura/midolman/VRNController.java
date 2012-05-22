/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.action.*;
import org.openflow.util.U16;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.actors.threadpool.Arrays;

import com.midokura.midolman.ForwardingElement.ForwardInfo;
import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.layer3.ServiceFlowController;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openflow.nxm.NxActionSetTunnelKey32;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchException;
import com.midokura.midolman.packets.*;
import com.midokura.midolman.portservice.PortService;
import com.midokura.midolman.portservice.VpnPortAgent;
import com.midokura.midolman.rules.ChainProcessor;
import com.midokura.midolman.rules.RuleResult;
import com.midokura.midolman.state.*;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;
import com.midokura.midolman.state.GreZkManager.GreKey;
import com.midokura.midolman.state.VpnZkManager.VpnType;
import com.midokura.midolman.util.Cache;


public class VRNController extends AbstractController
    implements ServiceFlowController, VRNControllerIface {

    private static final Logger log =
            LoggerFactory.getLogger(VRNController.class);

    // TODO(pino): These constants should be declared in openflow...
    public static final short NO_HARD_TIMEOUT = 0;
    public static final short NO_IDLE_TIMEOUT = 0;
    public static final short TEMPORARY_DROP_SECONDS = 5;
    public static final short NORMAL_IDLE_TIMEOUT = 20;
    static final short FLOW_PRIORITY = 10;
    private static final short SERVICE_FLOW_PRIORITY = FLOW_PRIORITY + 1;

    VRNCoordinator vrn;
    // TODO: Should we provide a setter or constructor parameter for this?
    private short idleFlowExpireSeconds = NORMAL_IDLE_TIMEOUT;

    private PortService bgpService;
    private PortService vpnService;
    private Map<UUID, List<Runnable>> bgpPortServicesById;
    // Store port num of a port that has a service port.
    private short serviceTargetPort;
    // Track which routers processed an installed flow.
    private Map<MidoMatch, Collection<UUID>> matchToRouters;
    // The controllers which make up the portsets.
    // TODO: Should this be part of PortZkManager?
    private PortSetMap portSetMap;
    // The local OVS ports in a portset.
    private Map<UUID, Set<Short>> localPortSetSlices;
    private DhcpHandler dhcpHandler;
    private Reactor reactor;
    private PortZkManager portMgr;
    private GreZkManager greMgr;
    private BridgeZkManager bridgeMgr;
    private ChainProcessor chainProcessor;
    private VpnZkManager vpnMgr;
    private Directory zkDir;
    PortConfigCache portCache;

    public VRNController(Directory zkDir, String zkBasePath,
            IntIPv4 localNwAddr, OpenvSwitchDatabaseConnection ovsdb,
            Reactor reactor, Cache cache, String externalIdKey, UUID vrnId,
            boolean useNxm, PortService bgpService, PortService vpnService)
                    throws StateAccessException {
        super(zkDir, zkBasePath, ovsdb, localNwAddr, externalIdKey, vrnId, useNxm);
        this.reactor = reactor;
        this.portMgr = new PortZkManager(zkDir, zkBasePath);
        this.greMgr = new GreZkManager(zkDir, zkBasePath);
        this.bridgeMgr = new BridgeZkManager(zkDir, zkBasePath);
        this.vpnMgr = new VpnZkManager(zkDir, zkBasePath);
        this.portSetMap = new PortSetMap(zkDir, zkBasePath);
        this.portSetMap.start();
        this.chainProcessor = new ChainProcessor(zkDir, zkBasePath, cache,
                                                 reactor);
        this.portCache =
                new PortConfigCache(reactor, zkDir, zkBasePath);
        this.vrn = new VRNCoordinator(zkDir, zkBasePath, reactor, cache, this,
                portSetMap, chainProcessor, portCache);
        this.localPortSetSlices = new HashMap<UUID, Set<Short>>();

        this.bgpService = bgpService;
        if (bgpService != null)
            this.bgpService.setController(this);

        this.vpnService = vpnService;

        this.bgpPortServicesById = new HashMap<UUID, List<Runnable>>();
        this.matchToRouters = new HashMap<MidoMatch, Collection<UUID>>();
        this.zkDir = zkDir;
        this.dhcpHandler = new DhcpHandler(zkDir, zkBasePath, this, portCache);
    }

    public void subscribePortSet(UUID portSetID)
            throws StateAccessException {
        portSetMap.addIPv4Addr(portSetID, publicIp);
        localPortSetSlices.put(portSetID, new HashSet<Short>());
    }

    public void unsubscribePortSet(UUID portSetID)
            throws StateAccessException {
        portSetMap.deleteIPv4Addr(portSetID, publicIp);
        localPortSetSlices.remove(portSetID);
    }

    public void addLocalPortToSet(UUID portSetID, UUID portID) {
        Set<Short> portSetSlice = localPortSetSlices.get(portSetID);
        if (null == portSetSlice) {
            log.error("addLocalPortToSet - no PortSet for {}", portSetID);
            return;
        }
        Integer portNum = portUuidToNumberMap.get(portID);
        if (null != portNum)
            portSetSlice.add(portNum.shortValue());
    }

    public void removeLocalPortFromSet(UUID portSetID, UUID portID) {
        Set<Short> portSetSlice = localPortSetSlices.get(portSetID);
        if (null == portSetSlice) {
            log.error("removeLocalPortFromSet - no PortSet for {}", portSetID);
            return;
        }
        Integer portNum = portUuidToNumberMap.get(portID);
        if (null != portNum)
            portSetSlice.remove(portNum.shortValue());
    }

    // TODO(pino): fix this quick hack that's used for DHCP replies.
    public void sendPacket(byte[] packet, UUID portId) {
        Integer portNum = portUuidToNumberMap.get(portId);
        if (null == portNum) {
            log.error("Can't find the OF port number for this virtual port.");
            return;
        }
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(portNum.shortValue(), (short) 0));
        controllerStub.sendPacketOut(ControllerStub.UNBUFFERED_ID,
                OFPort.OFPP_NONE.getValue(), actions, packet);
    }

    /**
     * This inner class is used to re-launch processing of un-PAUSED packets
     * in the main thread.  This also supports re-pausing packets.
     */
    class PacketContinuation implements Runnable {
        ForwardInfo fwdInfo;

        private PacketContinuation(ForwardInfo fwdInfo) {
            this.fwdInfo = fwdInfo;
        }

        @Override
        public void run() {
            log.debug("continue simulation of {}", fwdInfo);
            try {
                vrn.handleProcessResult(fwdInfo);
                if (fwdInfo.action != ForwardingElement.Action.PAUSED)
                    handleProcessResult(fwdInfo);
                else
                    log.debug("Pausing packet simulation.");
            } catch (Exception e) {
                log.error("Error processing packet:", e);
            }
        }
    }

    /**
     * For use by ForwardingElements. Invoke simulation for an internally-
     * generated packet. The simulation should start at the port where the
     * packet leaves the device that generated it.
     *
     * @param pkt
     *      The packet to inject into the virtual network.
     * @param originPort
     *      The port from which the packet is emitted. This may be a logical
     *      or materialized port.
     */
    public void addGeneratedPacket(Ethernet pkt, UUID originPort) {
        log.debug("Schedule simulation of packet output from {}", originPort);
        reactor.submit(new PacketContinuation(
                new GeneratedPacketContext(pkt, originPort)));
    }

    /**
     * The ForwardingElement that PAUSED a packet should use this method to
     * release ownership of the ForwardInfo and trigger the continuation of the
     * simulation of the packet's traversal of the virtual network.
     *
     * @param fwdInfo
     *      The packet context of a previously PAUSED packet.
     */
    public void continueProcessing(final ForwardInfo fwdInfo) {
        log.debug("Schedule simulation of paused packet {}", fwdInfo);
        reactor.submit(new PacketContinuation(fwdInfo));
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short shortInPort,
            byte[] data, long matchingTunnelId) {
        int inPort = shortInPort & 0xffff;
        ByteBuffer bb = ByteBuffer.wrap(data, 0, data.length);
        Ethernet ethPkt = new Ethernet();
        try {
            ethPkt.deserialize(bb);
        } catch (MalformedPacketException ex) {
            // Packet could not be deserialized: Drop it.
            log.warn("onPacketIn: malformed packet from port {}: {}",
                     inPort, ex.getMessage());
            MidoMatch m = new MidoMatch();
            // Usually we avoid OFMatch.loadFromPacket, but here we can't use
            // AbstractController.createMatchFromPacket.
            // We can't use OFMatch.loadFromPacket either, as that throws
            // on a malformed packet.
            // TODO: Create a match-creating method which works as far as
            // it can on malformed packets.
            //m.loadFromPacket(data, shortInPort);
            //installDropFlowEntry(m, bufferId, NO_IDLE_TIMEOUT,
            //        TEMPORARY_DROP_SECONDS);
            return;
        }
        log.debug("onPacketIn: port {} received buffer {} of size {} - {}",
                new Object [] { inPort, bufferId, totalLen, ethPkt });
        MidoMatch match = AbstractController.createMatchFromPacket(
                ethPkt, shortInPort);

        // Handle tunneled packets.
        if (super.isTunnelPortNum(inPort)) {
            forwardTunneledPkt(match, bufferId, inPort, data, matchingTunnelId);
            return;
        }

        // The packet isn't from a tunnel port.
        UUID inPortId = portNumToUuid.get(inPort);
        if (null == inPortId) {
            log.warn("onPacketIn: received a packet from port {}. " +
                    "The port is not a tunnel nor virtual.", inPort, match);
            // Drop all packets received on this port for a while.
            MidoMatch flowMatch = new MidoMatch();
            flowMatch.setInputPort(shortInPort);
            installDropFlowEntry(flowMatch, bufferId, NO_IDLE_TIMEOUT,
                    TEMPORARY_DROP_SECONDS);
            return;
        }

        // check if the packet is a DHCP request
        if (ethPkt.getEtherType() == IPv4.ETHERTYPE) {
            IPv4 ipv4 = (IPv4) ethPkt.getPayload();
            if (ipv4.getProtocol() == UDP.PROTOCOL_NUMBER) {
                UDP udp = (UDP) ipv4.getPayload();
                if (udp.getSourcePort() == 68 && udp.getDestinationPort() == 67) {
                    DHCP dhcp = (DHCP) udp.getPayload();
                    if (dhcp.getOpCode() == DHCP.OPCODE_REQUEST) {
                        log.debug("onPacketIn: got a DHCP bootrequest");
                        try {
                            if (dhcpHandler.handleDhcpRequest(inPortId, dhcp,
                                    ethPkt.getSourceMACAddress())) {
                                freeBuffer(bufferId);
                                return;
                            }
                        } catch (StateAccessException e) {
                            installDropFlowEntry(match, bufferId,
                                    NO_IDLE_TIMEOUT, TEMPORARY_DROP_SECONDS);
                            return;
                        }
                    }
                }
            }
        }

        // Send the packet to the network simulation.
        OFPacketContext fwdInfo = new OFPacketContext(
                bufferId, data, inPort, totalLen, matchingTunnelId);
        fwdInfo.inPortId = inPortId;
        fwdInfo.flowMatch = match;
        fwdInfo.matchIn = match.clone();
        fwdInfo.pktIn = ethPkt;
        try {
            vrn.process(fwdInfo);
        } catch (Exception e) {
            log.warn("Exception during network simulation, " +
                    "installing temporary drop rule: ", e);
            freeFlowResources(match, fwdInfo.getNotifiedFEs());
            installDropFlowEntry(match, bufferId, NO_IDLE_TIMEOUT,
                    TEMPORARY_DROP_SECONDS);
            return;
        }
        if (fwdInfo.action != ForwardingElement.Action.PAUSED)
            handleProcessResult(fwdInfo);
        else
            log.debug("Pausing packet simulation.");
    }

    public void forwardTunneledPkt(MidoMatch match, int bufferId,
                                   int inPort, byte[] data, long tunnelId) {
        log.debug("Tunnel {} with GRE key {} received pkt with match {}",
                new Object[] {inPort, tunnelId, match });
        // Convert the tunnelId to a UUID.  The tunnelId is a GRE key, use
        // GreZkManager to find the owner UUID.
        UUID destPortId;
        try {
            ZkNodeEntry<Integer, GreKey> entry = greMgr.get((int)tunnelId);
            if (entry != null)
                destPortId = entry.value.ownerId;
            else {
                log.error("Failed to get vport ID for tunnel ID {}. " +
                        "Installing temporary DROP rule", tunnelId);
                installDropFlowEntry(match, bufferId, NO_IDLE_TIMEOUT,
                        TEMPORARY_DROP_SECONDS);
                return;
            }
        } catch (StateAccessException e) {
            log.error("Couldn't get port ID for tunnel ID {}: ZooKeeper error "+
                      "{}", tunnelId, e.getMessage());
            installDropFlowEntry(match, bufferId, NO_IDLE_TIMEOUT,
                    TEMPORARY_DROP_SECONDS);
            return;
        }
        Set<Short> outPorts = new HashSet<Short>();
        if (portSetMap.containsKey(destPortId)) { // multiple egress
            log.debug("forwardTunneledPkt: to PortSet.");
            // Add local OVS ports.
            if (localPortSetSlices.containsKey(destPortId)) {
                for (short outPort : localPortSetSlices.get(destPortId)) {
                    if (doesPortFilterAcceptFloodedPacket(outPort, match))
                        outPorts.add(outPort);
                }
            }
        } else { // single egress
            Integer portNum = super.portUuidToNumberMap.get(destPortId);
            if (null == portNum) {
                log.warn("forwardTunneledPkt unrecognized egress port.");
            } else {
                log.warn("forwardTunneledPkt: to single egress.");
                outPorts.add(portNum.shortValue());
            }
        }
        if (outPorts.size() == 0) {
            log.warn("forwardTunneledPkt: DROP - no OVS ports to output to.");
            installDropFlowEntry(match, bufferId,
                    NO_IDLE_TIMEOUT, TEMPORARY_DROP_SECONDS);
            return;
        }
        log.debug("forwardTunneledPkt: sending to ports {}", outPorts);
        // TODO(pino): avoid installing flows for controller-generated ARP/ICMP?
        // TODO(pino): wildcard everything except inPort and tunnelId?
        List<OFAction> actions = makeActionsForFlow(match, match, outPorts, 0);
        addFlowAndSendPacket(bufferId, match, idleFlowExpireSeconds,
                NO_HARD_TIMEOUT, false, actions, data, tunnelId);
    }

    private void handleProcessResult(ForwardInfo fwdInfo) {
        // TODO: should we assert that fwdInfo.action != PAUSED?

        OFPacketContext ofPktCtx = null;
        GeneratedPacketContext genPktCtx = null;
        if (fwdInfo.isGeneratedPacket())
            genPktCtx = GeneratedPacketContext.class.cast(fwdInfo);
        else
            ofPktCtx = OFPacketContext.class.cast(fwdInfo);

        switch (fwdInfo.action) {
        case DROP:
            log.debug("handleProcessResult: DROP {}", fwdInfo);
            if (null != ofPktCtx)
                installDropFlowEntry(fwdInfo.flowMatch, ofPktCtx.bufferId,
                        NO_IDLE_TIMEOUT, TEMPORARY_DROP_SECONDS);
            freeFlowResources(fwdInfo.flowMatch, fwdInfo.getNotifiedFEs());
            return;
        case NOT_IPV4:
            log.debug("handleProcessResult: NOT_IPV4 {}", fwdInfo);
            if (null != ofPktCtx) {
                // Wildcard everything but dl_type and dl_dst. We only want to
                // drop the NOT_IPV4 flows that are passing through routers.
                MidoMatch flowMatch;
                flowMatch = new MidoMatch();
                flowMatch.setDataLayerType(
                        fwdInfo.flowMatch.getDataLayerType());
                flowMatch.setDataLayerDestination(
                        fwdInfo.flowMatch.getDataLayerDestination());
                // The flow should be temporary because the topology might
                // change so that the router is no longer in the packet's path.
                installDropFlowEntry(flowMatch, ofPktCtx.bufferId,
                        NO_IDLE_TIMEOUT, NO_HARD_TIMEOUT);
            }
            freeFlowResources(fwdInfo.flowMatch, fwdInfo.getNotifiedFEs());
            return;
        case CONSUMED:
            log.debug("handleProcessResult: CONSUMED {}", fwdInfo);
            if (null != ofPktCtx)
                freeBuffer(ofPktCtx.bufferId);
            return;
        case FORWARD:
            log.debug("forward the packet.");
            forwardLocalPacket(fwdInfo);
            return;
        default:
            log.error("handleProcessResult: unrecognized action {}",
                    fwdInfo.action);
            throw new RuntimeException("Unrecognized forwarding Action "
                    + fwdInfo.action);
        }
    }

    private void forwardLocalPacket(ForwardInfo fwdInfo) {
        OFPacketContext ofPktCtx = null;
        GeneratedPacketContext genPktCtx = null;
        int inPortNum = Integer.MAX_VALUE;
        if (fwdInfo.isGeneratedPacket())
            genPktCtx = GeneratedPacketContext.class.cast(fwdInfo);
        else {
            ofPktCtx = OFPacketContext.class.cast(fwdInfo);
            inPortNum = ofPktCtx.inPortNum;
        }

        List<OFAction> actions;
        Integer outPortNum = portUuidToNumberMap.get(fwdInfo.outPortId);
        // Is the egress a locally installed virtual port?
        if (null != outPortNum) {
            actions = makeActionsForFlow(fwdInfo.flowMatch,
                    fwdInfo.matchOut, outPortNum.shortValue(), 0);
            // TODO(pino): wildcard some of the match's fields.
            log.debug("FORWARD {} to OF port {} with actions {}",
                    new Object[] { fwdInfo, outPortNum, actions });

            if (null != ofPktCtx) {
                // Remember the routers that need flow-removal notification.
                log.debug("Installing a new flow entry for {}.",
                        fwdInfo.flowMatch);
                boolean removalNotification = !fwdInfo.getNotifiedFEs()
                                                      .isEmpty();
                if (removalNotification)
                    matchToRouters.put(fwdInfo.flowMatch,
                                       fwdInfo.getNotifiedFEs());
                addFlowAndSendPacket(ofPktCtx.bufferId, fwdInfo.flowMatch,
                        idleFlowExpireSeconds, NO_HARD_TIMEOUT,
                        removalNotification, actions, ofPktCtx.data, 0);
            } else { // generated packet
                log.debug("Forwarding a generated packet.");
                controllerStub.sendPacketOut(
                        ControllerStub.UNBUFFERED_ID,
                        OFPort.OFPP_NONE.getValue(),
                        actions, genPktCtx.data);
                // TODO(pino): free the flow resources for generated packets?
            }
            return;
        }
        // the egress port is either remote or multiple.
        Set<Short> outPorts = new HashSet<Short>();
        int greKey;
        if (portSetMap.containsKey(fwdInfo.outPortId)) { // multiple egress
            log.debug("forwardPacket: FORWARD to PortSet {}: {}",
                      fwdInfo.outPortId, fwdInfo);
            // Add local OVS ports.
            if (localPortSetSlices.containsKey(fwdInfo.outPortId)) {
                for (short localPortNum :
                         localPortSetSlices.get(fwdInfo.outPortId)) {
                    // For port sets, never go out the ingress port.
                    if (localPortNum == inPortNum)
                        continue;
                    if (doesPortFilterAcceptFloodedPacket(localPortNum,
                                                      fwdInfo.matchOut))
                        outPorts.add(localPortNum);
                }
            }
            IPv4Set controllersAddrs = portSetMap.get(fwdInfo.outPortId);
            if (controllersAddrs == null)
                log.error("forwardPacket: no hosts for portset ID {}",
                        fwdInfo.outPortId);
            else for (String controllerAddr : controllersAddrs.getStrings()) {
                log.debug("forwardPacket: {} within port set {}",
                          controllerAddr, fwdInfo.outPortId);
                IntIPv4 target = IntIPv4.fromString(controllerAddr);
                // Skip the local controller.
                if (target.equals(publicIp))
                    continue;
                Integer portNum = tunnelPortNumOfPeer(target);
                if (portNum != null)
                    outPorts.add(portNum.shortValue());
                else
                    log.warn("forwardPacket: No OVS tunnel port found " +
                             "for Controller at {}", controllerAddr);
            }
            // Extract the greKey from the Bridge config (only PortSet for now).
            // TODO(pino): cache the BridgeConfigs to reduce ZK calls.
            try {
                // TODO: Shouldn't this be fetching from a PortSet datastore,
                // not bridgeMgr?
                ZkNodeEntry<UUID, BridgeConfig> entry =
                        bridgeMgr.get(fwdInfo.outPortId);
                greKey = entry.value.greKey;
            } catch (StateAccessException e) {
                // TODO(pino): drop this flow.
                log.error("Got error mapping the port set to a GRE key.", e);
                return;
            }
        } else { // single egress
            log.debug("FORWARD to single egress.");
            Integer tunPortNum =
                    super.portUuidToTunnelPortNumber(fwdInfo.outPortId);
            if (null == tunPortNum) {
                IntIPv4 intAddress = portLocMap.get(fwdInfo.outPortId);
                log.warn("forwardPacket: No tunnel to port {} and host {}",
                        fwdInfo.outPortId, intAddress);
            } else
                outPorts.add(tunPortNum.shortValue());
            // Extract the greKey from the PortConfig
            PortConfig portConfig = portCache.get(fwdInfo.outPortId);
            greKey = portConfig.greKey;
        }
        if (outPorts.size() == 0) {
            log.warn("forwardPacket: DROP - no OVS ports to output to.");
            if (null != ofPktCtx)
                installDropFlowEntry(fwdInfo.flowMatch, ofPktCtx.bufferId,
                         NO_IDLE_TIMEOUT, TEMPORARY_DROP_SECONDS);
            freeFlowResources(fwdInfo.flowMatch, fwdInfo.getNotifiedFEs());
            return;
        }
        log.debug("forwardPacket: sending to ports {}", outPorts);
        actions = makeActionsForFlow(fwdInfo.flowMatch, fwdInfo.matchOut,
                outPorts, greKey);
        if (null != ofPktCtx) {
            // Track the routers for this flow so we can free resources
            // when the flow is removed.
            boolean removalNotification = !fwdInfo.getNotifiedFEs().isEmpty();
            if (removalNotification)
                matchToRouters.put(fwdInfo.flowMatch, fwdInfo.getNotifiedFEs());
            log.debug("installing a flow entry for this packet.");
            addFlowAndSendPacket(ofPktCtx.bufferId, fwdInfo.flowMatch,
                    idleFlowExpireSeconds, NO_HARD_TIMEOUT,
                    removalNotification, actions, ofPktCtx.data, 0);
        } else { // generated packet
            log.debug("Not installing a flow entry for this generated packet.");
            controllerStub.sendPacketOut(
                    ControllerStub.UNBUFFERED_ID, OFPort.OFPP_NONE.getValue(),
                    actions, genPktCtx.data);
            // TODO(pino): free the flow resources for generated packets?
        }
    }

    private boolean doesPortFilterAcceptFloodedPacket(short portNum,
                                                      MidoMatch mmatch) {
        try {
            UUID portID = portNumToUuid.get(new Integer(portNum));
            PortConfig portCfg = portCache.get(portID);
            MidoMatch pktMatch = mmatch.clone();
            // Ports themselves don't have ports for packets to be entering/
            // exiting, so set inputPort and outputPort to null.
            // The port groups *should* be set based on the original origin
            // port, but we don't have access to that, so use null.
            RuleResult result = chainProcessor.applyChain(
                            portCfg.outboundFilter, pktMatch, pktMatch,
                            null, null, portID, null);
            if (!mmatch.equals(result.match)) {
                log.warn("Outbound port filter {} attempted to change " +
                         "flooded packet.", portCfg.outboundFilter);
            }
            return result.action.equals(RuleResult.Action.ACCEPT);
        } catch (StateAccessException e) {
            log.error("Got ZooKeeper error {} trying to get the " +
                      "outbound filter for OVS port {} -- adding " +
                      "it to the flood", e.getMessage(), portNum);
            return true;
        }
    }

    private List<OFAction> makeActionsForFlow(MidoMatch origMatch,
            MidoMatch newMatch, short outPortNum, int setTunnelId) {
        Set<Short> portSet = new HashSet<Short>();
        portSet.add(outPortNum);
        return makeActionsForFlow(origMatch, newMatch, portSet, setTunnelId);
    }

    /**
     * Create an action list that transforms origMatch into newMatch, sets
     * the tunnelId (if not zero) and outputs to a set of OF ports.
     *
     * @param origMatch
     * @param newMatch
     * @param outPorts
     *      The set of OF port numbers for which output actions should be added.
     * @param setTunnelId
     *      Ignored it zero. Otherwise, an action to set the tunnelId is added.
     * @return
     *      An ordered list of actions that ends with all the output actions.
     */
    private List<OFAction> makeActionsForFlow(MidoMatch origMatch,
            MidoMatch newMatch, Set<Short> outPorts, int setTunnelId) {
        // Create OF actions for fields that changed from original to last
        // match.
        List<OFAction> actions = new ArrayList<OFAction>();
        OFAction action = null;
        if (!Arrays.equals(origMatch.getDataLayerSource(),
                           newMatch.getDataLayerSource())) {
            action = new OFActionDataLayerSource();
            ((OFActionDataLayer) action).setDataLayerAddress(
                        newMatch.getDataLayerSource());
            actions.add(action);
        }
        if (!Arrays.equals(origMatch.getDataLayerDestination(),
                           newMatch.getDataLayerDestination())) {
            action = new OFActionDataLayerDestination();
            ((OFActionDataLayer) action).setDataLayerAddress(
                    newMatch.getDataLayerDestination());
            actions.add(action);
        }
        if (origMatch.getNetworkSource() != newMatch.getNetworkSource()) {
            action = new OFActionNetworkLayerSource();
            ((OFActionNetworkLayerAddress) action).setNetworkAddress(
                    newMatch.getNetworkSource());
            actions.add(action);
        }
        if (origMatch.getNetworkDestination() !=
                newMatch.getNetworkDestination()) {
            action = new OFActionNetworkLayerDestination();
            ((OFActionNetworkLayerAddress) action).setNetworkAddress(
                    newMatch.getNetworkDestination());
            actions.add(action);
        }
        if (origMatch.getTransportSource() != newMatch.getTransportSource()) {
            action = new OFActionTransportLayerSource();
            ((OFActionTransportLayer) action).setTransportPort(
                    newMatch.getTransportSource());
            actions.add(action);
        }
        if (origMatch.getTransportDestination() !=
                newMatch.getTransportDestination()) {
            action = new OFActionTransportLayerDestination();
            ((OFActionTransportLayer) action).setTransportPort(
                newMatch.getTransportDestination());
            actions.add(action);
        }
        if (0 != setTunnelId)
            actions.add(new NxActionSetTunnelKey32(setTunnelId));
        for (Short outPortNum : outPorts) {
            short out = outPortNum.shortValue();
            /* See OpenFlow 1.0, enum ofp_port {...
             * OFPP_IN_PORT	= 0xfff8,
             * Send the packet out the input port. This virtual port must be
             * explicitly used in order to send back out of the input port.
             */
            if (out == origMatch.getInputPort())
                out = OFPort.OFPP_IN_PORT.getValue();
            actions.add(new OFActionOutput(out, (short) 0));
        }
        return actions;
    }

    private void addFlowAndSendPacket(int bufferId, OFMatch match,
            short idleTimeoutSecs, short hardTimeoutSecs,
            boolean sendFlowRemove, List<OFAction> actions,
            byte[] data, long matchingTunnelId) {
        controllerStub.sendFlowModAdd(match, 0, idleTimeoutSecs,
                hardTimeoutSecs, FLOW_PRIORITY, bufferId, sendFlowRemove,
                false, false, actions, matchingTunnelId);
        // Unbuffered packets need to be explicitly sent.
        if (bufferId == ControllerStub.UNBUFFERED_ID)
            controllerStub.sendPacketOut(bufferId, OFPort.OFPP_NONE.getValue(),
                    actions, data);
    }

    private void installDropFlowEntry(MidoMatch flowMatch, int bufferId,
                                      short idleTimeout, short hardTimeout) {
        if (bufferId != ControllerStub.UNBUFFERED_ID)
            controllerStub.sendFlowModAdd(flowMatch, (long) 0, idleTimeout,
                    hardTimeout, FLOW_PRIORITY, bufferId, false, false, false,
                    new ArrayList<OFAction>());
    }

    private void installPredefTunnelRule(int portNum, int greKey) {
        // Install a rule {match:[TunnelID = greKey] action:[output(portNum)]}
        MidoMatch match = new MidoMatch();
        ArrayList<OFAction> actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput((short) portNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                                      NO_HARD_TIMEOUT, FLOW_PRIORITY, -1,
                                      false, false, false, actions, greKey);
    }

    private void removePredefTunnelRule(int portNum, int greKey) {
        // Remove the rule {match:[TunnelID = greKey] action:[output(portNum)]}
        // (strict match)
        MidoMatch match = new MidoMatch();
        controllerStub.sendFlowModDelete(match, true, FLOW_PRIORITY,
                                         (short) portNum, greKey);
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount, long matchingTunnelId) {
        log.debug("onFlowRemoved: match {} reason {}", match, reason);

        // TODO(pino): do we care why the flow was removed?
        Collection<UUID> routers = matchToRouters.get(match);
        log.debug("onFlowRemoved: found routers {} for match {}", routers, match);
        if (null != routers) {
            freeFlowResources(match, routers);
        }
    }

    public void freeFlowResources(OFMatch match, Collection<UUID> forwardingElements) {
        // TODO(pino): are FEs mapping the correct match for invalidation?
        for (UUID feId : forwardingElements) {
            try {
                ForwardingElement fe = vrn.getForwardingElement(feId,
                        VRNCoordinator.FEType.DontConstruct);
                fe.freeFlowResources(match,
                        portNumToUuid.get(U16.f(match.getInputPort())));
            } catch (KeeperException e) {
                log.warn("freeFlowResources failed for match {} in FE {} -"
                        + " caught: \n{}",
                        new Object[] { match, feId, e.getStackTrace() });
            } catch (ZkStateSerializationException e) {
                log.warn("freeFlowResources failed for match {} in FE {} -"
                        + " caught: \n{}",
                        new Object[] { match, feId, e.getStackTrace() });
            } catch (StateAccessException e) {
                log.warn("freeFlowResources failed for match {} in FE {} -"
                        + " caught: \n{}",
                        new Object[] { match, feId, e.getStackTrace() });
            }
        }
    }

    @Override
    public void setServiceFlows(short localPortNum, short remotePortNum,
            int localAddr, int remoteAddr, short localTport, short remoteTport) {
        // Remember service's target port assuming that service flows sent
        // this packet to the OFPP_CONTROLLER.
        // TODO(yoshi): replace this with better mechanism such as ARP proxy
        // for service ports.
        serviceTargetPort = remotePortNum;

        // local to remote.
        MidoMatch match = new MidoMatch();
        match.setInputPort(localPortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
        match.setNetworkSource(localAddr);
        match.setNetworkDestination(remoteAddr);
        match.setTransportDestination(remoteTport);
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(remotePortNum, (short) 0));
        // OFPP_NONE is placed since outPort should be ignored. cf. OpenFlow
        // specification 1.0 p.15.
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        match = new MidoMatch();
        match.setInputPort(localPortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
            match.setNetworkSource(localAddr);
        match.setNetworkDestination(remoteAddr);
        match.setTransportSource(localTport);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(remotePortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        // remote to local.
        match = new MidoMatch();
        match.setInputPort(remotePortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
        match.setNetworkSource(remoteAddr);
        match.setNetworkDestination(localAddr);
        match.setTransportDestination(localTport);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(localPortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        match = new MidoMatch();
        match.setInputPort(remotePortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
        match.setNetworkSource(remoteAddr);
        match.setNetworkDestination(localAddr);
        match.setTransportSource(remoteTport);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(localPortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        // ARP flows.
        match = new MidoMatch();
        match.setInputPort(localPortNum);
        match.setDataLayerType(ARP.ETHERTYPE);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(remotePortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        match = new MidoMatch();
        match.setInputPort(remotePortNum);
        match.setDataLayerType(ARP.ETHERTYPE);
        // Output to both service port and controller port. Output to
        // OFPP_CONTROLLER requires to set non-zero value to max_len, and we
        // are setting the standard max_len (128 bytes) in OpenFlow.
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(localPortNum, (short) 0));
        actions.add(new OFActionOutput(OFPort.OFPP_CONTROLLER.getValue(),
                (short) 128));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        // ICMP flows.
        // Only valid for the service port with specified address.
        match = new MidoMatch();
        match.setInputPort(localPortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(ICMP.PROTOCOL_NUMBER);
        match.setNetworkSource(localAddr);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(remotePortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        match = new MidoMatch();
        match.setInputPort(remotePortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(ICMP.PROTOCOL_NUMBER);
        match.setNetworkDestination(localAddr);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(localPortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);
    }

    private void startBgpPortService(final short portNum, final UUID portId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException, IOException, StateAccessException {
        // If the materiazlied router port isn't discovered yet, try
        // setting flows between BGP peers later.

        if (portUuidToNumberMap.containsKey(portId)) {
            bgpService.start(datapathId, portNum,
                    portUuidToNumberMap.get(portId).shortValue());
        } else {
            if (!bgpPortServicesById.containsKey(portId)) {
                bgpPortServicesById.put(portId, new ArrayList<Runnable>());
            }
            List<Runnable> watchers = bgpPortServicesById.get(portId);
            watchers.add(new Runnable() {
                public void run() {
                    try {
                        bgpService.start(datapathId, portNum,
                                portUuidToNumberMap.get(portId).shortValue());
                    } catch (Exception e) {
                        log.warn("startPortService", e);
                    }
                }
            });
        }
    }

    private void setupBgpServicePort(int portNum, String portName)
        throws StateAccessException, IOException, KeeperException,
               InterruptedException, OpenvSwitchException.NotFoundException {
        UUID portId = bgpService.getRemotePort(portName);
        if (portId != null) {
            bgpService.configurePort(portId, portName);
            startBgpPortService((short)portNum, portId);
        }
    }

    private void addBgpServicePort(UUID portId, MAC hwAddr)
            throws StateAccessException, KeeperException {
        Set<String> servicePorts = bgpService.getPorts(portId);
        if (!servicePorts.isEmpty()) {
            if (bgpPortServicesById.containsKey(portId)) {
                for (Runnable watcher : bgpPortServicesById.get(portId)) {
                    watcher.run();
                }
                return;
            }
        }
        bgpService.addPort(datapathId, portId, hwAddr);
    }

    @Override
    protected void portMoved(UUID portUuid, IntIPv4 oldAddr, IntIPv4 newAddr) {
        // Do nothing.
    }

    @Override
    public final void clear() {
        // Destroy the Coordinator, which in turn destroys all the FEs.
        vrn.destroy();
    }

    /*
    * Setup a flow that sends all DHCP request packets to the controller.
    * This is needed because we want the entire packet, not just a sample.
    */
    private void setFlowsForHandlingDhcpInController(short portNum) {
        log.debug("setFlowsForHandlingDhcpInController: on port {}", portNum);

        MidoMatch match = new MidoMatch();
        match.setInputPort(portNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(UDP.PROTOCOL_NUMBER);
        match.setTransportSource((short) 68);
        match.setTransportDestination((short) 67);

        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(OFPort.OFPP_CONTROLLER.getValue(),
                (short) 1024));

        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);
    }

    @Override
    protected void addVirtualPort(
            int portNum, String name, MAC hwAddr, UUID portId) {
        log.info("addVirtualPort number {} bound to vport {}", portNum, portId);
        try {
            vrn.addPort(portId);
            // TODO(pino): check with Yoshi - services only apply to L3 ports.
            PortConfig portConfig = portCache.get(portId);
            if (portConfig instanceof
                    PortDirectory.MaterializedRouterPortConfig) {
                PortDirectory.MaterializedRouterPortConfig rtrPort =
                        PortDirectory.MaterializedRouterPortConfig.class.cast(
                                portConfig);
                rtrPort.setHwAddr(hwAddr);
                portMgr.update(
                        new ZkNodeEntry<UUID, PortConfig>(portId, portConfig));
                addBgpServicePort(portId, hwAddr);
            }

            // Install flows for this port's greKey to go directly to this port.
            int greKey = portConfig.greKey;
            installPredefTunnelRule(portNum, greKey);
        } catch (Exception e) {
            log.error("addVirtualPort", e);
        }
        setFlowsForHandlingDhcpInController((short)portNum);
    }

    @Override
    protected void deleteVirtualPort(int portNum, UUID portId) {
        log.info("deletePort number {} bound to virtual port {}",
                portNum, portId);
        try {
            PortConfig portConfig = portCache.get(portId);
            vrn.removePort(portId);
            removePredefTunnelRule(portNum, portConfig.greKey);
        } catch (Exception e) {
            log.error("deleteVirtualPort", e);
        }
    }

    @Override
    protected void addServicePort(int num, String name, UUID vId) {
        try {
            setupBgpServicePort(num, name);
        } catch (Exception e) {
            log.error("addServicePort", e);
        }
    }

    @Override
    protected void deleteServicePort(int num, String name, UUID vId) {
        // TODO: handle the removal of a service port.
    }

    @Override
    protected void addTunnelPort(int num, IntIPv4 peerIP) {
        // Do nothing.
    }

    @Override
    protected void deleteTunnelPort(int num, IntIPv4 peerIP) {
        // Do nothing.
    }

    @Override
    protected void initServicePorts(long datapathId) {

        long sessionId = zkDir.getSessionId();

        // Initialize VPN service.
        VpnPortAgent vpnAgent = new VpnPortAgent(sessionId, datapathId,
                vpnMgr);

        vpnAgent.setPortService(VpnType.OPENVPN_SERVER, this.vpnService);
        vpnAgent.setPortService(VpnType.OPENVPN_TCP_SERVER, this.vpnService);
        vpnAgent.setPortService(VpnType.OPENVPN_TCP_CLIENT, this.vpnService);
        vpnAgent.start();
    }

    private static class OFPacketContext extends ForwardInfo {
        int bufferId;
        int totalLen;
        int inPortNum;
        byte[] data;
        long tunnelId;

        private OFPacketContext(int bufferId, byte[] data, int inPortNum,
                                int totalLen, long tunnelId) {
            super(false);
            this.bufferId = bufferId;
            this.data = data;
            this.inPortNum = inPortNum;
            this.totalLen = totalLen;
            this.tunnelId = tunnelId;
        }
    }

    private static class GeneratedPacketContext extends ForwardInfo {
        byte[] data;

        private GeneratedPacketContext(Ethernet pkt, UUID originPort) {
            super(true);
            this.data = pkt.serialize();
            this.pktIn = pkt;

            this.action = ForwardingElement.Action.FORWARD;
            this.outPortId = originPort;
            this.matchOut = AbstractController.createMatchFromPacket(
                    pkt, (short) 0);
            this.flowMatch = this.matchOut.clone();
        }
    }
}
