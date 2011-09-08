package com.midokura.midolman.layer3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionDataLayer;
import org.openflow.protocol.action.OFActionDataLayerDestination;
import org.openflow.protocol.action.OFActionDataLayerSource;
import org.openflow.protocol.action.OFActionNetworkLayerAddress;
import org.openflow.protocol.action.OFActionNetworkLayerDestination;
import org.openflow.protocol.action.OFActionNetworkLayerSource;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.action.OFActionTransportLayer;
import org.openflow.protocol.action.OFActionTransportLayerDestination;
import org.openflow.protocol.action.OFActionTransportLayerSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.actors.threadpool.Arrays;

import com.midokura.midolman.AbstractController;
import com.midokura.midolman.L3DevicePort;
import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.layer3.Router.Action;
import com.midokura.midolman.layer3.Router.ForwardInfo;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.RouterPortConfig;
import com.midokura.midolman.state.PortLocationMap;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.RouterDirectory;
import com.midokura.midolman.util.Callback;

public class NetworkController extends AbstractController {

    private static final Logger log = LoggerFactory
            .getLogger(NetworkController.class);

    // TODO(pino): This constant should be declared in openflow...
    private static final short OFP_FLOW_PERMANENT = 0;
    private static final short TUNNEL_EXPIRY_SECONDS = 5;
    private static final short ICMP_EXPIRY_SECONDS = 5;
    public static final short IDLE_TIMEOUT_SECS = 0;
    private static final short FLOW_PRIORITY = 0;
    public static final int ICMP_TUNNEL = 0x05;

    private PortDirectory portDir;
    Network network;
    private Map<UUID, L3DevicePort> devPortById;
    private Map<Short, L3DevicePort> devPortByNum;
    private Reactor reactor;
    // Remove these once integrated with AbstractController
    Map<Integer, Short> peerIpToPortNum = new HashMap<Integer, Short>();
    Set<Short> tunnelPortNums = new HashSet<Short>();
    PortToIntNwAddrMap portIdToUnderlayIp;

    public NetworkController(int datapathId, UUID deviceId, int greKey,
            PortLocationMap dict, long idleFlowExpireMillis, int localNwAddr,
            RouterDirectory routerDir, PortDirectory portDir,
            OpenvSwitchDatabaseConnection ovsdb, Reactor reactor,
            PortToIntNwAddrMap locMap) {
        super(datapathId, deviceId, greKey, ovsdb, dict, 0, 0,
              idleFlowExpireMillis, null);
        // TODO Auto-generated constructor stub
        this.portDir = portDir;
        this.network = new Network(deviceId, routerDir, portDir, reactor);
        this.reactor = reactor;
        this.devPortById = new HashMap<UUID, L3DevicePort>();
        this.devPortByNum = new HashMap<Short, L3DevicePort>();
        portIdToUnderlayIp = locMap;
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort, byte[] data) {
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, inPort);
        L3DevicePort devPortOut;
        if (tunnelPortNums.contains(inPort)) {
            // TODO: Check for multicast packets we generated ourself for a
            // group we're in, and drop them.

            // TODO: Check for the broadcast address, and if so use the
            // broadcast
            // ethernet address for the dst MAC.
            // We can check the broadcast address by looking up the gateway in
            // Zookeeper to get the prefix length of its network.

            // TODO: Do address spoofing prevention: if the source
            // address doesn't match the vport's, drop the flow.

            // Extract the gateway IP and vport uuid.
            DecodedMacAddrs portsAndGw = decodeMacAddrs(match
                    .getDataLayerSource(), match.getDataLayerDestination());
            // If we don't own the egress port, there was a forwarding mistake.
            devPortOut = devPortById.get(portsAndGw.lastEgressPortId);
            if (null == devPortOut) {
                // TODO: raise an exception or install a Blackhole?
                return;
            }
            TunneledPktArpCallback cb = new TunneledPktArpCallback(bufferId,
                    totalLen, inPort, data, match, portsAndGw);
            network.getMacForIp(portsAndGw.lastEgressPortId,
                    portsAndGw.gatewayNwAddr, cb);
            // The ARP will be completed asynchronously by the callback.
            return;
        }

        // Else it's a packet from a materialized port.
        L3DevicePort devPortIn = devPortByNum.get(inPort);
        if (null == devPortIn) {
            // drop packets entering on ports that we don't recognize.
            // TODO: free the buffer.
            return;
        }
        Ethernet ethPkt = new Ethernet();
        ethPkt.deserialize(data, 0, data.length);
        // Drop the packet if it's not addressed to an L2 mcast address or
        // the ingress port's own address.
        // TODO(pino): check this with Jacob.
        if (!Arrays.equals(ethPkt.getDestinationMACAddress(), devPortIn
                .getMacAddr())
                && !ethPkt.isMcast()) {
            installBlackhole(match, bufferId, OFP_FLOW_PERMANENT);
            return;
        }
        ForwardInfo fwdInfo = new ForwardInfo();
        fwdInfo.inPortId = devPortIn.getId();
        fwdInfo.matchIn = match.clone();
        fwdInfo.pktIn = ethPkt;
        Set<UUID> routers = new HashSet<UUID>();
        try {
            network.process(fwdInfo, routers);
        } catch (Exception e) {
            return;
        }
        boolean useWildcards = false; // TODO(pino): replace with real config.

        MidoMatch flowMatch;
        switch (fwdInfo.action) {
        case BLACKHOLE:
            // TODO(pino): the following wildcarding seems too aggressive.
            // If wildcards are enabled, wildcard everything but nw_src and
            // nw_dst.
            // This is meant to protect against DOS attacks by preventing ipc's
            // to
            // the Openfaucet controller if mac addresses or tcp ports are
            // cycled.
            if (useWildcards)
                flowMatch = makeWildcarded(match);
            else
                flowMatch = match;
            installBlackhole(flowMatch, bufferId, OFP_FLOW_PERMANENT);
            notifyFlowAdded(match, flowMatch, devPortIn.getId(), fwdInfo,
                    routers);
            return;
        case CONSUMED:
            freeBuffer(bufferId);
            return;
        case FORWARD:
            // If the egress port is local, ARP and forward the packet.
            devPortOut = devPortById.get(fwdInfo.outPortId);
            if (null != devPortOut) {
                LocalPktArpCallback cb = new LocalPktArpCallback(bufferId,
                        totalLen, devPortIn, data, match, fwdInfo, ethPkt,
                        routers);
                network.getMacForIp(fwdInfo.outPortId, fwdInfo.gatewayNwAddr,
                        cb);
            } else { // devPortOut is null; the egress port is remote.
                Short tunPortNum = null;
                Integer peerAddr = portIdToUnderlayIp.get(fwdInfo.outPortId);
                if (null != peerAddr)
                     tunPortNum = peerIpToPortNum.get(peerAddr);
                if (null == tunPortNum) {
                    log.warn("Could not find location or tunnel port number "
                            + "for Id " + fwdInfo.outPortId.toString());
                    installBlackhole(match, bufferId, ICMP_EXPIRY_SECONDS);
                    // TODO: check whether this is the right error code (host?).
                    sendICMPforLocalPkt(ICMP.UNREACH_CODE.UNREACH_NET,
                            devPortIn.getId(), ethPkt, fwdInfo.inPortId,
                            fwdInfo.pktIn, fwdInfo.outPortId);
                    return;
                }
                byte[] dlSrc = new byte[6];
                byte[] dlDst = new byte[6];
                setDlHeadersForTunnel(dlSrc, dlDst, PortDirectory
                        .UUID32toInt(fwdInfo.inPortId), PortDirectory
                        .UUID32toInt(fwdInfo.outPortId), fwdInfo.gatewayNwAddr);
                fwdInfo.matchOut.setDataLayerSource(dlSrc);
                fwdInfo.matchOut.setDataLayerDestination(dlDst);
                List<OFAction> ofActions = makeActionsForFlow(match,
                        fwdInfo.matchOut, tunPortNum);
                // TODO(pino): should we do any wildcarding here?
                // TODO(pino): choose the correct hard and idle timeouts.
                addFlowAndSendPacket(bufferId, match, OFP_FLOW_PERMANENT,
                        IDLE_TIMEOUT_SECS, true, ofActions, inPort, data);
            }
            return;
        case NOT_IPV4:
            // If wildcards are enabled, wildcard everything but dl_type. One
            // rule per ethernet protocol type catches all non-IPv4 flows.
            if (useWildcards) {
                short dlType = match.getDataLayerType();
                match = new MidoMatch();
                match.setDataLayerType(dlType);
            }
            installBlackhole(match, bufferId, OFP_FLOW_PERMANENT);
            return;
        case NO_ROUTE:
            // Intentionally use an exact match for this drop rule.
            // TODO(pino): wildcard the L2 fields.
            installBlackhole(match, bufferId, ICMP_EXPIRY_SECONDS);
            // Send an ICMP
            sendICMPforLocalPkt(ICMP.UNREACH_CODE.UNREACH_NET, devPortIn
                    .getId(), ethPkt, fwdInfo.inPortId, fwdInfo.pktIn,
                    fwdInfo.outPortId);
            // This rule is temporary, don't notify the flow checker.
            return;
        case REJECT:
            // Intentionally use an exact match for this drop rule.
            installBlackhole(match, bufferId, ICMP_EXPIRY_SECONDS);
            // Send an ICMP
            sendICMPforLocalPkt(ICMP.UNREACH_CODE.UNREACH_FILTER_PROHIB,
                    devPortIn.getId(), ethPkt, fwdInfo.inPortId, fwdInfo.pktIn,
                    fwdInfo.outPortId);
            // This rule is temporary, don't notify the flow checker.
            return;
        default:
            throw new RuntimeException("Unrecognized forwarding Action type.");
        }
    }

    private List<OFAction> makeActionsForFlow(MidoMatch origMatch,
            MidoMatch newMatch, short outPortNum) {
        // Create OF actions for fields that changed from original to last
        // match.
        List<OFAction> actions = new ArrayList<OFAction>();
        OFAction action = null;
        if (!Arrays.equals(origMatch.getDataLayerSource(), newMatch
                .getDataLayerSource())) {
            action = new OFActionDataLayerSource();
            ((OFActionDataLayer) action).setDataLayerAddress(newMatch
                    .getDataLayerSource());
            actions.add(action);
        }
        if (!Arrays.equals(origMatch.getDataLayerDestination(), newMatch
                .getDataLayerDestination())) {
            action = new OFActionDataLayerDestination();
            ((OFActionDataLayer) action).setDataLayerAddress(newMatch
                    .getDataLayerDestination());
            actions.add(action);
        }
        if (origMatch.getNetworkSource() != newMatch.getNetworkSource()) {
            action = new OFActionNetworkLayerSource();
            ((OFActionNetworkLayerAddress) action).setNetworkAddress(newMatch
                    .getNetworkSource());
            actions.add(action);
        }
        if (origMatch.getNetworkDestination() != newMatch
                .getNetworkDestination()) {
            action = new OFActionNetworkLayerDestination();
            ((OFActionNetworkLayerAddress) action).setNetworkAddress(newMatch
                    .getNetworkDestination());
            actions.add(action);
        }
        if (origMatch.getTransportSource() != newMatch.getTransportSource()) {
            action = new OFActionTransportLayerSource();
            ((OFActionTransportLayer) action).setTransportPort(newMatch
                    .getTransportSource());
            actions.add(action);
        }
        if (origMatch.getTransportDestination() != newMatch
                .getTransportDestination()) {
            action = new OFActionTransportLayerDestination();
            ((OFActionTransportLayer) action).setTransportPort(newMatch
                    .getTransportDestination());
            actions.add(action);
        }
        action = new OFActionOutput(outPortNum, (short) 0);
        actions.add(action);
        return actions;
    }

    private MidoMatch makeWildcardedFromTunnel(MidoMatch m1) {
        // TODO Auto-generated method stub
        return m1;
    }

    public static void setDlHeadersForTunnel(byte[] dlSrc, byte[] dlDst,
            int lastInPortId, int lastEgPortId, int gwNwAddr) {
        // Set the data layer source and destination:
        // The ingress port is used as the high 32 bits of the source mac.
        // The egress port is used as the low 32 bits of the dst mac.
        // The high 16 bits of the gwNwAddr are the low 16 bits of the src mac.
        // The low 16 bits of the gwNwAddr are the high 16 bits of the dst mac.
        for (int i = 0; i < 4; i++)
            dlSrc[i] = (byte) (lastInPortId >> (3 - i) * 8);
        dlSrc[4] = (byte) (gwNwAddr >> 24);
        dlSrc[5] = (byte) (gwNwAddr >> 16);
        dlDst[0] = (byte) (gwNwAddr >> 8);
        dlDst[1] = (byte) (gwNwAddr);
        for (int i = 2; i < 6; i++)
            dlDst[i] = (byte) (lastEgPortId >> (5 - i) * 8);
    }

    public static class DecodedMacAddrs {
        UUID lastIngressPortId;
        UUID lastEgressPortId;
        int gatewayNwAddr;
    }

    public static DecodedMacAddrs decodeMacAddrs(final byte[] src,
            final byte[] dst) {
        DecodedMacAddrs result = new DecodedMacAddrs();
        int port32BitId = 0;
        for (int i = 0; i < 4; i++)
            port32BitId |= src[i] << (3 - i) * 8;
        result.lastIngressPortId = PortDirectory.intTo32BitUUID(port32BitId);
        result.gatewayNwAddr = src[4] << 24;
        result.gatewayNwAddr |= src[5] << 16;
        result.gatewayNwAddr |= dst[0] << 8;
        result.gatewayNwAddr |= dst[1];
        port32BitId = 0;
        for (int i = 2; i < 6; i++)
            port32BitId |= dst[i] << (5 - i) * 8;
        result.lastEgressPortId = PortDirectory.intTo32BitUUID(port32BitId);
        return result;
    }

    private class TunneledPktArpCallback implements Callback<byte[]> {
        public TunneledPktArpCallback(int bufferId, int totalLen, short inPort,
                byte[] data, MidoMatch match, DecodedMacAddrs portsAndGw) {
            super();
            this.bufferId = bufferId;
            this.totalLen = totalLen;
            this.inPort = inPort;
            this.data = data;
            this.match = match;
            this.portsAndGw = portsAndGw;
        }

        int bufferId;
        int totalLen;
        short inPort;
        byte[] data;
        MidoMatch match;
        DecodedMacAddrs portsAndGw;

        @Override
        public void call(byte[] mac) {
            if (null != mac) {
                L3DevicePort devPort = devPortById
                        .get(portsAndGw.lastEgressPortId);
                if (null == devPort) {
                    // TODO(pino): do we need to do anything for this?
                    // The port was removed while we waited for the ARP.
                    return;
                }
                MidoMatch newMatch = match.clone();
                // TODO(pino): get the port's mac address from the ZK config.
                newMatch.setDataLayerSource(devPort.getMacAddr());
                newMatch.setDataLayerDestination(mac);
                List<OFAction> ofActions = makeActionsForFlow(match, newMatch,
                        devPort.getNum());
                boolean useWildcards = true; // TODO: get this from config.
                if (useWildcards) {
                    // TODO: Should we check for non-load-balanced routes and
                    // wild-card flows matching them on layer 3 and lower?
                    // inPort, dlType, nwSrc.
                    match = makeWildcardedFromTunnel(match);
                }
                // If this is an ICMP error message from a peer controller,
                // don't install a flow match, just send the packet
                if (PortDirectory.UUID32toInt(portsAndGw.lastIngressPortId) == ICMP_TUNNEL)
                    NetworkController.super.controllerStub.sendPacketOut(
                            bufferId, inPort, ofActions, data);
                else
                    addFlowAndSendPacket(bufferId, match,
                            TUNNEL_EXPIRY_SECONDS, IDLE_TIMEOUT_SECS, true,
                            ofActions, inPort, data);

            } else {
                installBlackhole(match, bufferId, ICMP_EXPIRY_SECONDS);
                // Send an ICMP !H
                // The packet came over a tunnel so its mac addresses were used
                // to encode Midonet port ids. Set the mac addresses to make
                // sure they don't run afoul of the ICMP error rules.
                Ethernet ethPkt = new Ethernet();
                ethPkt.deserialize(data, 0, data.length);
                sendICMPforTunneledPkt(ICMP.UNREACH_CODE.UNREACH_HOST, ethPkt,
                        portsAndGw.lastIngressPortId,
                        portsAndGw.lastEgressPortId);
            }
        }
    }

    private void addFlowAndSendPacket(int bufferId, OFMatch match,
            short hardTimeoutSecs, short idleTimeoutSecs,
            boolean sendFlowRemove, List<OFAction> actions, short inPort,
            byte[] data) {
        controllerStub.sendFlowModAdd(match, 0, idleTimeoutSecs, FLOW_PRIORITY,
                bufferId, sendFlowRemove, false, false, actions, inPort);
        // If packet was unbuffered, we need to explicitly send it otherwise the
        // flow won't be applied to it.
        if (bufferId == ControllerStub.UNBUFFERED_ID)
            controllerStub.sendPacketOut(bufferId, inPort, actions, data);
    }

    private class LocalPktArpCallback implements Callback<byte[]> {
        public LocalPktArpCallback(int bufferId, int totalLen,
                L3DevicePort devPortIn, byte[] data, MidoMatch match,
                ForwardInfo fwdInfo, Ethernet ethPkt, Set<UUID> traversedRouters) {
            super();
            this.bufferId = bufferId;
            this.totalLen = totalLen;
            this.inPort = devPortIn;
            this.data = data;
            this.match = match;
            this.fwdInfo = fwdInfo;
            this.ethPkt = ethPkt;
            this.traversedRouters = traversedRouters;
        }

        int bufferId;
        int totalLen;
        L3DevicePort inPort;
        byte[] data;
        MidoMatch match;
        ForwardInfo fwdInfo;
        Ethernet ethPkt;
        Set<UUID> traversedRouters;

        @Override
        public void call(byte[] mac) {
            if (null != mac) {
                L3DevicePort devPort = devPortById.get(fwdInfo.outPortId);
                if (null == devPort) {
                    // TODO(pino): do we need to do anything for this?
                    // The port was removed while we waited for the ARP.
                    return;
                }
                fwdInfo.matchOut.setDataLayerSource(devPort.getMacAddr());
                fwdInfo.matchOut.setDataLayerDestination(mac);
                List<OFAction> ofActions = makeActionsForFlow(match,
                        fwdInfo.matchOut, devPort.getNum());
                boolean useWildcards = false; // TODO: get this from config.
                if (useWildcards) {
                    // TODO: Should we check for non-load-balanced routes and
                    // wild-card flows matching them on layer 3 and lower?
                    // inPort, dlType, nwSrc.
                    match = makeWildcarded(match);
                }
                addFlowAndSendPacket(bufferId, match, OFP_FLOW_PERMANENT,
                        IDLE_TIMEOUT_SECS, true, ofActions, inPort.getNum(),
                        data);
            } else {
                installBlackhole(match, bufferId, ICMP_EXPIRY_SECONDS);
                // Send an ICMP !H
                sendICMPforLocalPkt(ICMP.UNREACH_CODE.UNREACH_HOST, inPort
                        .getId(), ethPkt, fwdInfo.inPortId, fwdInfo.pktIn,
                        fwdInfo.outPortId);
            }
            notifyFlowAdded(match, fwdInfo.matchOut, inPort.getId(), fwdInfo,
                    traversedRouters);
        }
    }

    /**
     * Send an ICMP Unreachable for a packet that arrived on a materialized port
     * that is not local to this controller. Equivalently, the packet was
     * received over a tunnel.
     * 
     * @param unreachCode
     *            The ICMP error code of ICMP Unreachable sent by this method.
     * @param tunneledEthPkt
     *            The original packet as it was received over the tunnel.
     * @param lastIngress
     *            The ingress port of the last router that handled the packet.
     * @param lastEgress
     *            The port from which the last router would have emitted the
     *            packet if it hadn't triggered an ICMP.
     */
    private void sendICMPforTunneledPkt(ICMP.UNREACH_CODE unreachCode,
            Ethernet tunneledEthPkt, UUID lastIngress, UUID lastEgress) {
        /*
         * We have a lot less information in the tunneled case compared to
         * non-tunneled packets. We only have the packet as it would have been
         * emitted by the egress port, not as it was seen at the ingress port of
         * the last router that handled the packet. That makes it hard to: 1)
         * correctly build the ICMP packet. 2) determine the materialized egress
         * port for the ICMP message. This will require invoking the routing
         * logic. 3) determine the next-hop gateway data link address. This
         * requires invoking the routing logic and then ARPing the next hop
         * gateway network address.
         */
        if (!canSendICMP(tunneledEthPkt, lastEgress))
            return;
        // First, we ask the last router to undo any transformation it may have
        // applied on the packet.
        network.undoRouterTransformation(tunneledEthPkt);
        ICMP icmp = new ICMP();
        IPv4 ipPktAtEgress = IPv4.class.cast(tunneledEthPkt.getPayload());
        icmp.setUnreachable(unreachCode, ipPktAtEgress);
        // The icmp packet will be emitted from the lastIngress port.
        IPv4 ip = new IPv4();
        RouterPortConfig portConfig;
        try {
            portConfig = network.getPortConfig(lastIngress);
        } catch (Exception e) {
            // Can't send the ICMP if we can't find the last egress port.
            return;
        }
        ip.setSourceAddress(portConfig.portAddr);
        ip.setDestinationAddress(ipPktAtEgress.getSourceAddress());
        ip.setProtocol(ICMP.PROTOCOL_NUMBER);
        ip.setPayload(icmp);
        Ethernet eth = new Ethernet();
        eth.setEtherType(IPv4.ETHERTYPE);
        eth.setPayload(ip);
        // Use fictitious mac addresses before routing.
        eth.setSourceMACAddress("02:a1:b2:c3:d4:e5");
        eth.setDestinationMACAddress("02:a1:b2:c3:d4:e6");
        byte[] data = eth.serialize();
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, (short) 0);
        ForwardInfo fwdInfo = new ForwardInfo();
        fwdInfo.matchIn = match;
        fwdInfo.pktIn = eth;
        if (portConfig instanceof MaterializedRouterPortConfig) {
            // The lastIngress port is materialized. Invoke the routing logic of
            // its router in order to find the network address of the next hop
            // gateway for the packet. Hopefully, the routing logic will agree
            // that the lastIngress port should emit the ICMP.
            Router rtr;
            try {
                rtr = network.getRouterByPort(lastIngress);
            } catch (Exception e) {
                log.warn("Dropping ICMP error message. Don't know where to "
                        + "forward because we failed to retrieve the router "
                        + "that would route it.");
                return;
            }
            rtr.process(fwdInfo);
            if (!fwdInfo.action.equals(Action.FORWARD)) {
                log.warn("Dropping ICMP error message. Don't know where to "
                        + "forward it because router.process didn't return "
                        + "FORWARD action.");
                return;
            }
            if (!fwdInfo.outPortId.equals(lastIngress)) {
                log.warn("Dropping ICMP error message. Would be emitted from"
                        + "a materialized port different from the materialized"
                        + "ingress port of the original packet.");
                return;
            }
        } else {
            // The lastIngress port is logical. Invoke the routing logic of the
            // router network, starting from the lastIngress's peer port.
            // Set the ICMP messages dl addresses to bogus addresses for
            // routing.
            Set<UUID> routerIds = new HashSet<UUID>();
            UUID peer_uuid = ((PortDirectory.LogicalRouterPortConfig) portConfig).peer_uuid;
            fwdInfo.inPortId = peer_uuid;
            try {
                network.process(fwdInfo, routerIds);
            } catch (Exception e) {
                log.warn("Dropping ICMP error message. Don't know where to "
                        + "forward it because network.process threw exception.");
                return;
            }
            if (!fwdInfo.action.equals(Action.FORWARD)) {
                log.warn("Dropping ICMP error message. Don't know where to "
                        + "forward it because network.process didn't return "
                        + "FORWARD action.");
                return;
            }
        }
        // TODO(pino): if the match changed, apply the changes to the packet.
        L3DevicePort devPort = devPortById.get(fwdInfo.outPortId);
        if (null != devPort) {
            // The packet came over the tunnel, but the ICMP is being sent to
            // a local port? This should be rare, let's log it and drop it.
            log.warn("Dropping ICMP error message. Original packet came from "
                    + "a tunnel, but the ICMP would be emitted from a local port");
            return;
        }
        // The ICMP will be tunneled. Encode the outPortId and next hop gateway
        // network address in the ethernet packet's address fields. For non-icmp
        // packets we usually encode the inPortId too, but it's only needed to
        // generate ICMPs so in this case it isn't needed. Instead we'll encode
        // a special value so the other end of the tunnel can recognize this
        // as a tunneled ICMP.
        setDlHeadersForTunnel(eth.getSourceMACAddress(), eth
                .getDestinationMACAddress(), ICMP_TUNNEL, PortDirectory
                .UUID32toInt(fwdInfo.outPortId), fwdInfo.gatewayNwAddr);
        Integer peerAddr = portIdToUnderlayIp.get(fwdInfo.outPortId);
        Short tunNum = null;
        if (null != peerAddr)
            tunNum = peerIpToPortNum.get(peerAddr);
        if (null == tunNum) {
            log.warn("Dropping ICMP error message. Can't find tunnel to peer"
                    + "port.");
            return;
        }
        sendUnbufferedPacketFromPort(eth, tunNum);
    }

    /**
     * Send an ICMP Unreachable for a packet that arrived on a materialized port
     * local to this controller.
     * 
     * @param unreachCode
     *            The ICMP error code of ICMP Unreachable sent by this method.
     * @param firstIngress
     *            The materialized port that received the original packet that
     *            entered the router network.
     * @param pktAtFirstIngress
     *            The original packet as seen by the firtIngress port.
     * @param lastIngress
     *            The ingress port of the last router that handled the packet.
     *            May be equal to the firstIngress, but could also be a logical
     *            port on a different router than firstIngress.
     * @param pktAtLastIngress
     *            The original packet as seen when it first entered the last
     *            router that handled it (and which triggered the ICMP).
     * @param lastEgress
     *            The port from which the last router would have emitted the
     *            packet if it hadn't triggered an ICMP. Null if it isn't known.
     */
    private void sendICMPforLocalPkt(ICMP.UNREACH_CODE unreachCode,
            UUID firstIngress, Ethernet pktAtFirstIngress, UUID lastIngress,
            Ethernet pktAtLastIngress, UUID lastEgress) {
        // Use the packet as seen by the last router to decide whether it's ok
        // to send an ICMP error message.
        if (!canSendICMP(pktAtLastIngress, lastEgress))
            return;
        // Build the ICMP as it would be built by the last router that handled
        // the original packet.
        ICMP icmp = new ICMP();
        icmp.setUnreachable(unreachCode, IPv4.class.cast(pktAtLastIngress
                .getPayload()));
        // The following ip packet to route the ICMP to its destination.
        IPv4 ip = new IPv4();
        // The packet's network address is that of the last ingress port.
        RouterPortConfig portConfig;
        try {
            portConfig = network.getPortConfig(lastIngress);
        } catch (Exception e) {
            // Can't send the ICMP if we can't find the last ingress port.
            return;
        }
        ip.setSourceAddress(portConfig.portAddr);
        // At this point, we should be using the source network address
        // from the ICMP payload as the ICMP's destination network address.
        // Then we'd have to inject the ICMP message into that last router and
        // allow it to invoke its routing logic. Instead, we cut corners here
        // and avoid the routing logic by inverting the fields in the original
        // packet as seen by the first ingress port.
        IPv4 ipPktAtFirstIngress = IPv4.class.cast(pktAtFirstIngress
                .getPayload());
        ip.setDestinationAddress(ipPktAtFirstIngress.getSourceAddress());
        ip.setProtocol(ICMP.PROTOCOL_NUMBER);
        ip.setPayload(icmp);
        // The following is the Ethernet packet for the ICMP message.
        Ethernet eth = new Ethernet();
        eth.setEtherType(IPv4.ETHERTYPE);
        eth.setPayload(ip);
        L3DevicePort firstIngressDevPort = devPortById.get(firstIngress);
        if (null == firstIngressDevPort) {
            // Can't send the ICMP if we no longer have the first ingress port.
            return;
        }
        eth.setSourceMACAddress(firstIngressDevPort.getMacAddr());
        eth.setDestinationMACAddress(pktAtFirstIngress.getSourceMACAddress());
        sendUnbufferedPacketFromPort(eth, firstIngressDevPort.getNum());
    }

    private void sendUnbufferedPacketFromPort(Ethernet ethPkt, short portNum) {
        OFActionOutput action = new OFActionOutput();
        action.setMaxLength((short) 0);
        action.setPort(portNum);
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(action);
        controllerStub.sendPacketOut(ControllerStub.UNBUFFERED_ID,
                OFPort.OFPP_CONTROLLER.getValue(), actions, ethPkt.serialize());
    }

    /**
     * Determine whether a packet can trigger an ICMP error. Per RFC 1812 sec.
     * 4.3.2.7, some packets should not trigger ICMP errors: 1) Other ICMP
     * errors. 2) Invalid IP packets. 3) Destined to IP bcast or mcast address.
     * 4) Destined to a link-layer bcast or mcast. 5) With source network prefix
     * zero or invalid source. 6) Second and later IP fragments.
     * 
     * @param ethPkt
     *            We wish to know whether this packet may trigger an ICMP error
     *            message.
     * @param egressPortId
     *            If known, this is the port that would have emitted the packet.
     *            It's used to determine whether the packet was addressed to an
     *            IP (local subnet) broadcast address.
     * @return True if-and-only-if the packet meets none of the above conditions
     *         - i.e. it can trigger an ICMP error message.
     */
    private boolean canSendICMP(Ethernet ethPkt, UUID egressPortId) {
        if (ethPkt.getEtherType() != IPv4.ETHERTYPE)
            return false;
        IPv4 ipPkt = IPv4.class.cast(ethPkt.getPayload());
        // Ignore ICMP errors.
        if (ipPkt.getProtocol() == ICMP.PROTOCOL_NUMBER) {
            ICMP icmpPkt = ICMP.class.cast(ipPkt.getPayload());
            if (icmpPkt.isError()) {
                log.info("Don't generate ICMP Unreachable for other ICMP "
                        + "errors.");
                return false;
            }
        }
        // TODO(pino): check the IP packet's validity - RFC1812 sec. 5.2.2
        // HERE
        // HERE
        // Ignore packets to IP mcast addresses.
        if (ipPkt.isMcast()) {
            log.info("Don't generate ICMP Unreachable for packets to an IP "
                    + "multicast address.");
            return false;
        }
        // Ignore packets sent to the local-subnet IP broadcast address of the
        // intended egress port.
        if (null != egressPortId) {
            RouterPortConfig portConfig;
            try {
                portConfig = network.getPortConfig(egressPortId);
            } catch (Exception e) {
                return false;
            }
            if (ipPkt.isSubnetBcast(portConfig.nwAddr, portConfig.nwLength)) {
                log.info("Don't generate ICMP Unreachable for packets to "
                        + "the subnet local broadcast address.");
                return false;
            }
        }
        // Ignore packets to Ethernet broadcast and multicast addresses.
        if (ethPkt.isMcast()) {
            log.info("Don't generate ICMP Unreachable for packets to "
                    + "Ethernet broadcast or multicast address.");
            return false;
        }
        // Ignore packets with source network prefix zero or invalid source.
        // TODO(pino): See RFC 1812 sec. 5.3.7
        if (ipPkt.getSourceAddress() == 0xffffffff
                || ipPkt.getDestinationAddress() == 0xffffffff) {
            log.info("Don't generate ICMP Unreachable for all-hosts broadcast "
                    + "packet");
            return false;
        }
        // TODO(pino): check this fragment offset
        // Ignore datagram fragments other than the first one.
        if (0 != (ipPkt.getFragmentOffset() & 0x1fff)) {
            log.info("Don't generate ICMP Unreachable for IP fragment packet");
            return false;
        }
        return true;
    }

    private void freeBuffer(int bufferId) {
        // If it's unbuffered, nothing to do.
        if (bufferId == ControllerStub.UNBUFFERED_ID)
            return;
        // TODO(pino): can we pass null instead of an empty action list?
        controllerStub.sendPacketOut(bufferId, (short) 0,
                new ArrayList<OFAction>(), null);
    }

    private void notifyFlowAdded(MidoMatch origMatch, MidoMatch flowMatch,
            UUID inPortId, ForwardInfo fwdInfo, Set<UUID> routers) {
        // TODO Auto-generated method stub

    }

    private void installBlackhole(MidoMatch flowMatch, int bufferId,
            int hardTimeout) {
        // TODO(pino): can we just send a null list instead of an empty list?
        List<OFAction> actions = new ArrayList<OFAction>();
        controllerStub.sendFlowModAdd(flowMatch, (long) 0, IDLE_TIMEOUT_SECS,
                (short) 0, bufferId, true, false, false, actions, (short) 0);
        // Note that if the packet was buffered, then the datapath will apply
        // the flow and drop it. If the packet was unbuffered, we don't need
        // to do anything.
    }

    private MidoMatch makeWildcarded(MidoMatch origMatch) {
        // TODO Auto-generated method stub
        return origMatch;
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount) {
        // TODO Auto-generated method stub

    }

    private L3DevicePort devPortOfPortDesc(OFPhysicalPort portDesc) {
        short portNum = portDesc.getPortNumber();
        L3DevicePort devPort = devPortByNum.get(portNum);
        if (devPort != null)
            return devPort;

        // Create a new one.
        UUID portId = getPortUuidFromOvsdb(datapathId, portNum);
        try {
            devPort = new L3DevicePort(portDir, portId, portNum,
                        portDesc.getHardwareAddress(), super.controllerStub);
        } catch (Exception e) {
            e.printStackTrace();
        }
        devPortById.put(portId, devPort);
        devPortByNum.put(portNum, devPort);

        return devPort;
    }

    @Override
    protected void addPort(OFPhysicalPort portDesc, short portNum) {
        L3DevicePort devPort = devPortOfPortDesc(portDesc);
        try {
	    network.addPort(devPort);
        } catch (Exception e) {
	    e.printStackTrace();
        }
    }

    @Override
    protected void deletePort(OFPhysicalPort portDesc) {
        L3DevicePort devPort = devPortOfPortDesc(portDesc);
	try {
            network.removePort(devPort);
        } catch (Exception e) {
	    e.printStackTrace();
        }
        devPortById.remove(devPort.getId());
        devPortByNum.remove(portDesc.getPortNumber());
    }

    @Override
    protected void modifyPort(OFPhysicalPort portDesc) {
        L3DevicePort devPort = devPortOfPortDesc(portDesc);
	//network.modifyPort(devPort);
        // FIXME: Call something in network.
    }

    public void onPortStatusTEMP(OFPhysicalPort port, OFPortReason status) {
        // Get the Midolman UUID from OVSDB.
        UUID portId = getPortUuidFromOvsdb(datapathId, port.getPortNumber());
        if (null == portId) {
            // TODO(pino): It might be a tunnel.
            return;
        }
        L3DevicePort devPort = null;
        // TODO(pino): need to look at both port.getState() and port.getConfig()
        // in order to figure out whether the port really is up/down for our
        // purposes.
        if (status.equals(OFPortReason.OFPPR_DELETE)) {
            // TODO(pino): handle the case of a tunnel port.
	    deletePort(port);
        } else if (status.equals(OFPortReason.OFPPR_ADD)) {
            short portNum = port.getPortNumber();
            addPort(port, portNum);
        }
        // TODO(pino): else if (status.equals(OFPortReason.OFPPR_MODIFY)) { ...
    }

    @Override
    public void clear() {
        // TODO Auto-generated method stub
    }

    @Override
    public void sendFlowModDelete(boolean strict, OFMatch match, int priority,
            int outPort) {
        // TODO Auto-generated method stub

    }

}
