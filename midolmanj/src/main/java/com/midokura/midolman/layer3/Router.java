/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.layer3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.openflow.protocol.OFMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.ICMP.UNREACH_CODE;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.rules.ChainProcessor;
import com.midokura.midolman.rules.RuleResult;
import com.midokura.midolman.state.*;
import com.midokura.midolman.state.PortDirectory.LogicalBridgePortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.RouterPortConfig;
import com.midokura.midolman.util.Callback0;
import com.midokura.midolman.util.Callback1;
import com.midokura.midolman.util.Net;
import com.midokura.midolman.vrn.ForwardInfo;
import com.midokura.midolman.vrn.ForwardingElement;
import com.midokura.midolman.vrn.VRNControllerIface;

/**
 * This class coordinates the routing logic for a single virtual router. It
 * delegates much of its internal logic to other class instances:
 * ReplicatedRoutingTable maintains the routing table and matches flows to
 * routes; ChainProcessor maintains the rule chains and applies their logic to
 * packets in the router.
 *
 * Router is directly responsible for three main tasks:
 * 1) Shepherding a packet through the pre-routing/routing/post-routing flow.
 * 2) Handling all ARP-related packets and maintaining ARP caches (per port).
 * 3) Populating the replicated routing table with routes to this router's
 *    ports that are materialized on the local host.
 *
 * @version ?
 * @author Pino de Candia
 */
public class Router implements ForwardingElement {

    private static final Logger log = LoggerFactory.getLogger(Router.class);

    public static final long ARP_RETRY_MILLIS = 10 * 1000;
    public static final long ARP_TIMEOUT_MILLIS = 60 * 1000;
    public static final long ARP_EXPIRATION_MILLIS = 3600 * 1000;
    public static final long ARP_STALE_MILLIS = 1800 * 1000;

    /**
     * Router uses one instance of this class to get a callback when there are
     * changes to the routes of any local materialized ports. The callback
     * keeps mirrors those changes to the shared/replicated routing table.
     * of any
     */
    private class PortListener implements L3DevicePort.Listener {
        @Override
        public void routesChanged(UUID portId, Collection<Route> added,
                Collection<Route> removed) {
            log.debug("routesChanged: added {} removed {}", added, removed);

            // The L3DevicePort keeps the routes up-to-date and notifies us
            // of changes so we can keep the replicated routing table in sync.
            for (Route rt : added) {
                log.debug("{} routesChanged adding {} to table", this, rt);
                try {
                    table.addRoute(rt);
                } catch (KeeperException e) {
                    log.warn("routesChanged", e);
                } catch (InterruptedException e) {
                    log.warn("routesChanged", e);
                }
            }
            for (Route rt : removed) {
                log.debug("{} routesChanged removing {} from table", this, rt);
                try {
                    table.deleteRoute(rt);
                } catch (KeeperException e) {
                    log.warn("routesChanged", e);
                }
            }
        }
    }

    protected UUID routerId;
    protected ChainProcessor ruleEngine;
    protected ReplicatedRoutingTable table;
    // Note that only materialized ports are tracked. Package visibility for
    // testing.
    Map<UUID, L3DevicePort> devicePorts;
    private PortListener portListener;
    private ArpTable arpTable;
    private Map<UUID, Map<Integer, List<Callback1<MAC>>>> arpCallbackLists;
    private Reactor reactor;
    private LoadBalancer loadBalancer;
    private final VRNControllerIface controller;
    private RouteZkManager routeMgr;
    private RouterZkManager routerMgr;
    private RouterZkManager.RouterConfig myConfig;
    private PortConfigCache portCache;

    public Router(UUID rtrId, Directory zkDir, String zkBasePath,
                  Reactor reactor, VRNControllerIface ctrl,
                  ChainProcessor chainProcessor, PortConfigCache portCache)
            throws StateAccessException {
        this.routerId = rtrId;
        this.reactor = reactor;
        this.controller = ctrl;
        this.portCache = portCache;
        this.routeMgr = new RouteZkManager(zkDir, zkBasePath);
        this.routerMgr = new RouterZkManager(zkDir, zkBasePath);
        table = new ReplicatedRoutingTable(routerId,
                        routerMgr.getRoutingTableDirectory(routerId),
                        CreateMode.EPHEMERAL);
        table.addWatcher(new Callback0() {
            @Override
            public void call() {
            if (null != controller)
                controller.invalidateFlowsByElement(routerId);
            }
        });
        table.start();
        // Get the Router's configuration and watch it for changes.
        myConfig = routerMgr.get(routerId,
                new Runnable() {
                    public void run() {
                        try {
                            RouterZkManager.RouterConfig config =
                                    routerMgr.get(routerId, this);
                            if (!myConfig.equals(config)) {
                                myConfig = config;
                                controller.invalidateFlowsByElement(routerId);
                            }
                        } catch (StateAccessException e) {
                            log.error("Failed to update router config", e);
                        }
                    }
                });
        arpTable = new ArpTable(routerMgr.getArpTableDirectory(routerId));
        arpTable.start();
        devicePorts = new HashMap<UUID, L3DevicePort>();
        portListener = new PortListener();
        arpCallbackLists = new HashMap<UUID, Map<Integer, List<Callback1<MAC>>>>();
        this.loadBalancer = new DummyLoadBalancer(table);
        arpTable.addWatcher(new ArpWatcher());
        ruleEngine = chainProcessor;
    }

    @Override
    public void destroy() {
        // TODO(pino): the router should completely tear down its state and
        // TODO: throw exceptions if it's called again. Old callbacks should
        // TODO: unregistered or result in no-ops.
        controller.invalidateFlowsByElement(routerId);
    }

    public String toString() {
        return routerId.toString();
    }

    @Override
    public UUID getId() {
        return routerId;
    }

    // This should only be called for materialized ports, not logical ports.
    @Override
    public void addPort(UUID portId) throws KeeperException,
            InterruptedException, StateAccessException {
        L3DevicePort port = new L3DevicePort(portCache, routeMgr, portId);
        devicePorts.put(portId, port);
        port.addListener(portListener);
        for (Route rt : port.getRoutes()) {
            log.debug("{} adding route {} to table", this, rt);
            table.addRoute(rt);
        }
    }

    // This should only be called for materialized ports, not logical ports.
    @Override
    public void removePort(UUID portId) throws StateAccessException {
        L3DevicePort port = devicePorts.get(portId);
        devicePorts.remove(portId);
        port.removeListener(portListener);
        for (Route rt : port.getRoutes()) {
            log.debug("{} removing route {} from table", this, rt);
            try {
                table.deleteRoute(rt);
            } catch (NoNodeException e) {
                log.warn("removePort got NoNodeException removing route.");
                throw new StateAccessException(e);
            } catch (KeeperException e) {
                log.warn("removePort got KeeperException removing route.");
                throw new StateAccessException(e);
            }
        }
        // TODO(pino): Any pending callbacks won't get called. Is that correct?
        arpCallbackLists.remove(portId);
    }

    /**
     * Get the mac address for an ip address that is accessible from a local
     * L3 port. The callback is invoked immediately within this method if the
     * address is in the ARP cache, otherwise the callback is invoked
     * asynchronously when the address is resolved or the ARP request times out,
     * whichever comes first.
     *
     * @param portId
     * @param nwAddr
     * @param cb
     */
    public void getMacForIp(UUID portId, int nwAddr, Callback1<MAC> cb)
            throws StateAccessException {
        IntIPv4 intNwAddr = new IntIPv4(nwAddr);
        log.debug("getMacForIp: port {} ip {}", portId, intNwAddr);

        RouterPortConfig rtrPortConfig =
                portCache.get(portId, RouterPortConfig.class);
        if (null == rtrPortConfig) {
            log.error("cannot get the configuration for the port {}", portId);
            cb.call(null);
            return;
        }
        String nwAddrStr = intNwAddr.toString();
        if (rtrPortConfig instanceof MaterializedRouterPortConfig) {
            MaterializedRouterPortConfig mPortConfig =
                    MaterializedRouterPortConfig.class.cast(rtrPortConfig);
            // The nwAddr should be in the port's localNwAddr/localNwLength.
            int shift = 32 - mPortConfig.localNwLength;
            // Shifts by 32 in java are no-ops (see
            // http://www.janeg.ca/scjp/oper/shift.html), so special case
            // nwLength=0 <=> shift=32 to always match.
            if ((nwAddr >>> shift) != (mPortConfig.localNwAddr >>> shift) &&
                    shift != 32) {
                log.warn("getMacForIp: {} cannot get mac for {} - address not "+
                         "in network segment of port {} ({}/{})",
                         new Object[] { this, nwAddrStr, portId,
                             mPortConfig.localNwAddr, mPortConfig.localNwLength
                         });
                log.warn("({} >>> {}) == {} != ({} >>> {}) == {}", new Object[]
                         { nwAddr, shift, nwAddr >>> shift,
                           mPortConfig.localNwAddr, shift,
                           mPortConfig.localNwAddr >>> shift });
                cb.call(null);
                return;
            }
        }
        ArpCacheEntry entry = arpTable.get(intNwAddr);
        long now = reactor.currentTimeMillis();
        if (null != entry && null != entry.macAddr) {
            if (entry.stale < now && entry.lastArp + ARP_RETRY_MILLIS < now) {
                // Note that ARP-ing to refresh a stale entry doesn't retry.
                log.debug("getMacForIp: {} getMacForIp refreshing ARP cache entry for {}",
                        this, nwAddrStr);
                generateArpRequest(nwAddr, portId, rtrPortConfig);
            }
            // TODO(pino): should this call be invoked asynchronously?
            cb.call(entry.macAddr);
            return;
        }
        // Generate an ARP if there isn't already one in flight and someone
        // is waiting for the reply. Note that there is one case when we
        // generate the ARP even if there's one in flight: when the previous
        // ARP was generated by a stale entry that was subsequently expired.
        Map<Integer, List<Callback1<MAC>>> cbLists =
                        arpCallbackLists.get(portId);
        if (null == cbLists) {
            // TODO(pino): remove this map of lists when it becomes empty again.
            cbLists = new HashMap<Integer, List<Callback1<MAC>>>();
            arpCallbackLists.put(portId, cbLists);
        }
        List<Callback1<MAC>> cbList = cbLists.get(nwAddr);
        if (null == cbList) {
            cbList = new ArrayList<Callback1<MAC>>();
            cbLists.put(nwAddr, cbList);
            log.debug("getMacForIp: {} getMacForIp generating ARP request for {}", this,
                    nwAddrStr);
            generateArpRequest(nwAddr, portId, rtrPortConfig);
            // TODO(pino): discuss with Jacob. If another controller is trying
            // TODO:       the ARP at the same time, why not detect it by
            // TODO:       reading the arpTable and checking for null MAC?
            try {
                arpTable.put(intNwAddr,
                    new ArpCacheEntry(null, now + ARP_TIMEOUT_MILLIS,
                                      now + ARP_RETRY_MILLIS, now));
            } catch (KeeperException e) {
                log.error("KeeperException adding ARP table entry", e);
            } catch (InterruptedException e) {
                log.error("InterruptedException adding ARP table entry", e);
            }
            // Schedule ARP retry and expiration.
            reactor.schedule(new ArpRetry(nwAddr, portId, rtrPortConfig),
                    ARP_RETRY_MILLIS, TimeUnit.MILLISECONDS);
            reactor.schedule(new ArpExpiration(nwAddr, portId),
                    ARP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } else {
            log.debug("Not ARP'ing for {} because an ARP is already " +
                      "outstanding.", nwAddr);
        }
        cbList.add(cb);
    }

    @Override
    public void process(ForwardInfo fwdInfo) throws StateAccessException {
        log.debug("{} process fwdInfo {}", this, fwdInfo);

        MAC hwDst = new MAC(fwdInfo.matchIn.getDataLayerDestination());
        RouterPortConfig rtrPortCfg =
                portCache.get(fwdInfo.inPortId, RouterPortConfig.class);
        if (null == rtrPortCfg) {
            log.error("Could not get the port's configuration {}",
                    fwdInfo.inPortId);
            fwdInfo.action = Action.DROP;
            return;
        }

        // Process packets that are either IPv4 or ARP with proto IPv4.
        if (fwdInfo.matchIn.getDataLayerType() != IPv4.ETHERTYPE
            && !(fwdInfo.matchIn.getDataLayerType() == ARP.ETHERTYPE
                     && ARP.class.cast(fwdInfo.pktIn.getPayload())
                        .getProtocolType() == IPv4.ETHERTYPE)) {
            fwdInfo.action = Action.NOT_IPV4;
            return;
        }

        if (Ethernet.isBroadcast(hwDst)) {
            // Handle ARP/IPv4 requests.
            // OpenFlow uses NetworkProtocol to encode the ARP opcode.
            if (fwdInfo.matchIn.getDataLayerType() == ARP.ETHERTYPE
                    && fwdInfo.matchIn.getNetworkProtocol() == ARP.OP_REQUEST) {
                processArpRequest(ARP.class.cast(fwdInfo.pktIn.getPayload()),
                        fwdInfo.inPortId, rtrPortCfg);
                fwdInfo.action = Action.CONSUMED;
            } else {
                // Otherwise drop the packet.
                fwdInfo.action = Action.DROP;
            }
            return;
        }

        // Drop the packet if it isn't addressed to the inPort's MAC.
        if (!hwDst.equals(rtrPortCfg.getHwAddr())) {
            fwdInfo.action = Action.DROP;
            log.warn("dlDst ({}) neither bcast nor inPort's addr ({})",
                     hwDst, rtrPortCfg.getHwAddr());
            return;
        }

        // Handle ARP replies, drop other ARPs.
        if (fwdInfo.matchIn.getDataLayerType() == ARP.ETHERTYPE) {
            // OpenFlow uses NetworkProtocol to encode the ARP opcode.
            if (fwdInfo.matchIn.getNetworkProtocol() == ARP.OP_REPLY) {
                processArpReply(ARP.class.cast(fwdInfo.pktIn.getPayload()),
                        fwdInfo.inPortId, rtrPortCfg);
                fwdInfo.action = Action.CONSUMED;
            } else {
                fwdInfo.action = Action.DROP;
            }
            return;
        }

        // Is the packet addressed to the inPort's own IP?
        IntIPv4 nwDst = new IntIPv4(fwdInfo.matchIn.getNetworkDestination());
        IntIPv4 inPortIP = new IntIPv4(rtrPortCfg.portAddr);
        if (nwDst.equals(inPortIP)) {
            // Handle ICMP echo requests.
            if (isIcmpEchoRequest(fwdInfo.matchIn)) {
                sendIcmpEchoReplyFromInPort(fwdInfo, rtrPortCfg);
                fwdInfo.action = Action.CONSUMED;
                // TODO(pino): drop flow for a while to prevent DOS attacks?
            } else { // Otherwise drop the packet.
                fwdInfo.action = Action.DROP;
            }
            return;
        }

        // Apply pre-routing rules.
        log.debug("{} apply pre-routing rules to {}", this, fwdInfo);

        fwdInfo.outPortId = null;
        RuleResult res = ruleEngine.applyChain(myConfig.inboundFilter,
                fwdInfo, fwdInfo.matchIn, this.routerId, false);
        if (res.trackConnection)
            fwdInfo.addRemovalNotification(routerId);
        if (res.action.equals(RuleResult.Action.DROP)) {
            fwdInfo.action = Action.DROP;
            return;
        }
        if (res.action.equals(RuleResult.Action.REJECT)) {
            fwdInfo.action = Action.DROP;
            sendICMPforLocalPkt(fwdInfo, UNREACH_CODE.UNREACH_FILTER_PROHIB);
            return;
        }
        if (!res.action.equals(RuleResult.Action.ACCEPT))
            throw new RuntimeException("Pre-routing returned an action other "
                    + "than ACCEPT, DROP or REJECT.");

        log.debug("Packet context {} passed pre-routing in router {}",
                  fwdInfo, this);
        // Do a routing table lookup.
        Route rt = loadBalancer.lookup(res.match);
        if (null == rt) {
            sendICMPforLocalPkt(fwdInfo, UNREACH_CODE.UNREACH_NET);
            fwdInfo.action = Action.DROP;
            return;
        }
        if (rt.nextHop.equals(Route.NextHop.BLACKHOLE)) {
            fwdInfo.action = Action.DROP;
            return;
        }
        if (rt.nextHop.equals(Route.NextHop.REJECT)) {
            sendICMPforLocalPkt(fwdInfo, UNREACH_CODE.UNREACH_FILTER_PROHIB);
            fwdInfo.action = Action.DROP;
            return;
        }
        if (!rt.nextHop.equals(Route.NextHop.PORT))
            throw new RuntimeException("Forwarding table returned next hop "
                    + "that isn't one of BLACKHOLE, NO_ROUTE, PORT or REJECT.");
        if (null == rt.nextHopPort) {
            log.error(
                    "{} route indicates forward to port, but no portId given",
                    this);
            // TODO(pino): should we remove this route?
            // For now just drop packets that match this route.
            fwdInfo.action = Action.DROP;
            return;
        }

        // Apply post-routing rules.
        log.debug("{} pkt next hop {} and egress port {} - apply post-routing.",
                new Object[] { this, IPv4.fromIPv4Address(rt.nextHopGateway),
                rt.nextHopPort });
        fwdInfo.outPortId = rt.nextHopPort;
        res = ruleEngine.applyChain(myConfig.outboundFilter, fwdInfo,
                                    res.match, this.routerId, false);
        if (res.trackConnection)
            fwdInfo.addRemovalNotification(routerId);
        if (res.action.equals(RuleResult.Action.DROP)) {
            fwdInfo.action = Action.DROP;
            return;
        }
        if (res.action.equals(RuleResult.Action.REJECT)) {
            sendICMPforLocalPkt(fwdInfo, UNREACH_CODE.UNREACH_FILTER_PROHIB);
            fwdInfo.action = Action.DROP;
            return;
        }
        if (!res.action.equals(RuleResult.Action.ACCEPT))
            throw new RuntimeException("Post-routing returned an action other "
                    + "than ACCEPT, DROP or REJECT.");

        fwdInfo.matchOut = res.match;
        RouterPortConfig outPortCfg = portCache.get(fwdInfo.outPortId,
                                                    RouterPortConfig.class);
        if (null == outPortCfg) {
            log.error("Can't find the configuration for the egress port {}",
                      fwdInfo.outPortId);
            fwdInfo.action = Action.DROP;
            return;
        }
        // Drop packet addressed to the outPort's own IP.
        IntIPv4 outPortIP = new IntIPv4(outPortCfg.portAddr);
        if (nwDst.equals(outPortIP)) {
            fwdInfo.action = Action.DROP;
            return;
        }
        // Set the hwSrc and hwDst before forwarding the packet.
        fwdInfo.matchOut.setDataLayerSource(outPortCfg.getHwAddr());
        if (outPortCfg instanceof LogicalRouterPortConfig) {
            LogicalRouterPortConfig logCfg =
                    LogicalRouterPortConfig.class.cast(outPortCfg);
            if (null == logCfg.peerId()) {
                // Not connected to anything.  Send ICMP unreachable
                log.warn("Packet forwarded to a dangling logical port: {}",
                        fwdInfo.outPortId);
                sendICMPforLocalPkt(fwdInfo, UNREACH_CODE.UNREACH_NET);
                fwdInfo.action = Action.DROP;
                return;
            }

            // If sending to a logical router port, just grab the MAC from
            // that port's PortConfig.  Else we have to ARP for the next hop's
            // MAC, unless it's already in our ARP cache.
            MAC peerMac = getPeerMac(logCfg);
            if (peerMac != null) {
                fwdInfo.action = Action.FORWARD;
                fwdInfo.matchOut.setDataLayerDestination(peerMac);
                return;
            }
        }

        fwdInfo.action = Action.PAUSED;
        int nextHopIP = rt.nextHopGateway;
        if (nextHopIP == 0 || nextHopIP == -1)
            nextHopIP = fwdInfo.matchOut.getNetworkDestination();
        ArpCallback cb = new ArpCallback(fwdInfo, true);
        getMacForIp(fwdInfo.outPortId, nextHopIP, cb);
        // If the callback hasn't yet been called, when it's called later this
        // will signal that it needs to call VRNController.continueProcessing.
        cb.inMethodProcess = false;
    }

    // If the peer exists and is a RouterPortConfig, return its MAC; else null.
    private MAC getPeerMac(LogicalRouterPortConfig logCfg) {
        // We don't know if the peer is a bridge or router port. Use the form of
        // PortConfigCache.get that will avoid class cast exceptions in the log.
        PortConfig peerConfig = portCache.get(logCfg.peerId());
        if (null == peerConfig) {
            log.error("No portConfig for {}'s peer port {} in ZK.",
                IPv4.fromIPv4Address(logCfg.portAddr), logCfg.peerId());
            return null;
        }
        if (peerConfig instanceof LogicalRouterPortConfig)
            return ((LogicalRouterPortConfig) peerConfig).hwAddr;
        return null;
    }

    private boolean isIcmpEchoRequest(OFMatch match) {
        return match.getNetworkProtocol() == ICMP.PROTOCOL_NUMBER
                && (match.getTransportSource() & 0xff) == ICMP.TYPE_ECHO_REQUEST
                && (match.getTransportDestination() & 0xff) == ICMP.CODE_NONE;
    }

    private class ArpCallback implements Callback1<MAC> {
        boolean inMethodProcess;
        ForwardInfo fwdInfo;

        private ArpCallback(ForwardInfo fwdInfo, boolean inMethodProcess) {
            this.fwdInfo = fwdInfo;
            this.inMethodProcess = inMethodProcess;
        }

        @Override
        public void call(MAC value) {
            if (null == value) {
                fwdInfo.action = Action.DROP;
                sendICMPforLocalPkt(fwdInfo, UNREACH_CODE.UNREACH_HOST);
            } else {
                fwdInfo.matchOut.setDataLayerDestination(value);
                fwdInfo.action = Action.FORWARD;
            }
            if (!inMethodProcess)
                controller.continueProcessing(fwdInfo);
        }
    }

    private void sendIcmpEchoReplyFromInPort(ForwardInfo fwdInfo,
                                             RouterPortConfig inPortCfg) {
        // Use the Ethernet packet only for the ICMP payload. Use the match
        // to fill the L2-L3 fields of the echo reply. The pktIn is the original
        // Ethernet packet sent by OpenFlow onPacketIn.
        IPv4 ipPkt = (IPv4)fwdInfo.pktIn.getPayload();
        if (ipPkt.getProtocol() != ICMP.PROTOCOL_NUMBER) {
            // This should never happen.
            log.error("Not an ICMP echo request.");
            return;
        }
        ICMP icmpPkt = (ICMP)ipPkt.getPayload();
        if (icmpPkt.getType() != ICMP.TYPE_ECHO_REQUEST ||
                icmpPkt.getCode() != ICMP.CODE_NONE) {
            // This should never happen.
            log.error("ICMP is not an echo request.");
            return;
        }
        // Generate the echo reply.
        ICMP icmpReply = new ICMP();
        icmpReply.setEchoReply(icmpPkt.getIdentifier(),
                icmpPkt.getSequenceNum(), icmpPkt.getData());
        IPv4 ipReply = new IPv4();
        ipReply.setPayload(icmpReply);
        ipReply.setProtocol(ICMP.PROTOCOL_NUMBER);
        ipReply.setDestinationAddress(fwdInfo.matchIn.getNetworkSource());
        ipReply.setSourceAddress(inPortCfg.portAddr);
        Ethernet ethReply = new Ethernet();
        ethReply.setPayload(ipReply);
        ethReply.setDestinationMACAddress(
                new MAC(fwdInfo.matchIn.getDataLayerSource()));
        ethReply.setSourceMACAddress(inPortCfg.getHwAddr());
        ethReply.setEtherType(IPv4.ETHERTYPE);
        log.debug("sending echo reply from {} to {}",
                IPv4.fromIPv4Address(ipReply.getSourceAddress()),
                IPv4.fromIPv4Address(ipReply.getDestinationAddress()));
        controller.addGeneratedPacket(ethReply, fwdInfo.inPortId);
    }

    private static boolean spoofL2Network(int hostNwAddr, int nwAddr,
            int nwLength, int localNwAddr, int localNwLength) {
        // Return true (spoof arp replies for hostNwAddr) if hostNwAddr is in
        // the nwAddr, but not in the localNwAddr.
        int shift = 32 - nwLength;
        boolean inNw = true;
        if (32 > shift)
            inNw = (hostNwAddr >>> shift) == (nwAddr >>> shift);
        if (!inNw)
            return false;
        shift = 32 - localNwLength;
        inNw = true;
        if (32 > shift)
            inNw = (hostNwAddr >>> shift) == (localNwAddr >>> shift);
        return !inNw;
    }

    private void processArpRequest(ARP arpPkt, UUID inPortId,
                                   RouterPortConfig rtrPortConfig) {
        log.debug("processArpRequest: arpPkt {} from port {}",
                arpPkt, rtrPortConfig);

        // If the request is for the ingress port's own address, it's for us.
        // Respond with the port's Mac address.
        // If the request is for an IP address which we emulate
        // switching for, we spoof the target host and respond to the ARP
        // with our own MAC address. These addresses are those in nw_prefix
        // but not local_nw_prefix for the in_port. (local_nw_prefix addresses
        // are assumed to be already handled by a switch.)

        // First get the ingress port's mac address
        boolean drop = true;
        int tpa = IPv4.toIPv4Address(arpPkt.getTargetProtocolAddress());
        if (tpa == rtrPortConfig.portAddr)
            drop = false;
        else if (rtrPortConfig instanceof MaterializedRouterPortConfig) {
            MaterializedRouterPortConfig mPortConfig =
                    MaterializedRouterPortConfig.class.cast(rtrPortConfig);
            if (spoofL2Network(tpa, mPortConfig.nwAddr, mPortConfig.nwLength,
                    mPortConfig.localNwAddr, mPortConfig.localNwLength))
                drop = false;
        }
        if (drop) {
            // It's an ARP for someone else. Ignore it.
            return;
        }
        // TODO(pino): If this ARP is for the port's own address don't reply
        // unless we've already confirmed no-one else is using this IP.
        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte) 6);
        arp.setProtocolAddressLength((byte) 4);
        arp.setOpCode(ARP.OP_REPLY);
        MAC portMac = rtrPortConfig.getHwAddr();
        arp.setSenderHardwareAddress(portMac);
        arp.setSenderProtocolAddress(arpPkt.getTargetProtocolAddress());
        arp.setTargetHardwareAddress(arpPkt.getSenderHardwareAddress());
        arp.setTargetProtocolAddress(arpPkt.getSenderProtocolAddress());
        int spa = IPv4.toIPv4Address(arpPkt.getSenderProtocolAddress());

        log.debug("{} replying to ARP request from {} for {} with own mac {}",
                new Object[] { this, IPv4.fromIPv4Address(spa),
                        IPv4.fromIPv4Address(tpa),
                        portMac});

        // TODO(pino) logging.
        Ethernet pkt = new Ethernet();
        pkt.setPayload(arp);
        pkt.setSourceMACAddress(portMac);
        pkt.setDestinationMACAddress(arpPkt.getSenderHardwareAddress());
        pkt.setEtherType(ARP.ETHERTYPE);

        // Now send it from the port.
        controller.addGeneratedPacket(pkt, inPortId);
    }

    private void processArpReply(ARP arpPkt, UUID inPortId,
                                 RouterPortConfig rtrPortConfig) {
        log.debug("processArpReply: arpPkt {} from port {}",
                arpPkt, rtrPortConfig);

        // Verify that the reply was meant for us: tpa is the port's nw addr,
        // and tha is the port's mac.
        int tpa = IPv4.toIPv4Address(arpPkt.getTargetProtocolAddress());
        MAC tha = arpPkt.getTargetHardwareAddress();
        if (tpa != rtrPortConfig.portAddr
                || !tha.equals(rtrPortConfig.getHwAddr())) {
            log.debug("{} ignoring ARP reply because its tpa or tha don't "
                    + "match ip addr or mac of port {}", this, inPortId);
            return;
        }
        // Question: Should we make noise if an ARP reply disagrees with the
        // existing arp_cache entry?

        MAC sha = arpPkt.getSenderHardwareAddress();
        IntIPv4 spa = new IntIPv4(arpPkt.getSenderProtocolAddress());
        log.debug("{} received an ARP reply with spa {} and sha {}",
                new Object[] { this, spa, sha });
        long now = reactor.currentTimeMillis();
        // TODO(pino): maybe avoid doing this if the current MAC in the ARP
        // TODO:       is the same and the expiration/stale times are close.
        // TODO:       Avoid unnecessary changes to the shared ARP table.
        ArpCacheEntry entry = new ArpCacheEntry(sha, now
                + ARP_EXPIRATION_MILLIS, now + ARP_STALE_MILLIS, 0);
        log.debug("Adding ARP cache entry for {} on port {}", spa, inPortId);
        try {
            arpTable.put(spa, entry);
        } catch (KeeperException e) {
            log.error("KeeperException storing ARP table entry", e);
        } catch (InterruptedException e) {
            log.error("InterruptedException storing ARP table entry", e);
        }
        reactor.schedule(new ArpExpiration(spa.getAddress(), inPortId),
                ARP_EXPIRATION_MILLIS, TimeUnit.MILLISECONDS);
        Map<Integer, List<Callback1<MAC>>> cbLists =
                arpCallbackLists.get(inPortId);
        if (null == cbLists) {
            log.debug("processArpReply: cbLists is null");
            return;
        }

        List<Callback1<MAC>> cbList = cbLists.remove(spa.getAddress());
        if (null != cbList)
            for (Callback1<MAC> cb : cbList)
                cb.call(sha);
    }

    private class ArpWatcher implements
            ReplicatedMap.Watcher<IntIPv4, ArpCacheEntry> {
        public void processChange(IntIPv4 ipAddr, ArpCacheEntry old,
                                  ArpCacheEntry new_) {
            if (new_ == null || new_.macAddr == null) {
                // Entry was deleted or added as "pending".
                return;
            }
            Integer ipInt = new Integer(ipAddr.addressAsInt());
            // Scan arpCallbackLists for ipAddr.
            for (Map.Entry<UUID, Map<Integer, List<Callback1<MAC>>>> entry :
                         arpCallbackLists.entrySet()) {
                List<Callback1<MAC>> cbList = entry.getValue().remove(ipInt);
                if (null != cbList)
                    for (Callback1<MAC> cb : cbList)
                        cb.call(new_.macAddr);
            }
        }
    }

    private class ArpExpiration implements Runnable {

        int nwAddr;
        UUID portId;

        ArpExpiration(int nwAddr, UUID inPortId) {
            this.nwAddr = nwAddr;
            this.portId = inPortId;
        }

        @Override
        public void run() {
            IntIPv4 intNwAddr = new IntIPv4(nwAddr);
            log.debug("ArpExpiration.run: ip {} port {}", intNwAddr, portId);

            ArpCacheEntry entry = arpTable.get(intNwAddr);
            if (null == entry) {
                // The entry has already been removed.
                log.debug("{} ARP expiration triggered for {} but cache "
                        + "entry has already been removed", this, intNwAddr);
                return;
            }
            if (entry.expiry <= reactor.currentTimeMillis()) {
                log.debug("{} expiring ARP cache entry for {}", this,
                        intNwAddr);
                try {
                    arpTable.removeIfOwner(intNwAddr);
                } catch (KeeperException e) {
                    log.error("KeeperException while removing ARP table entry", e);
                } catch (InterruptedException e) {
                    log.error("InterruptedException while removing ARP table entry", e);
                }
                Map<Integer, List<Callback1<MAC>>> cbLists =
                                arpCallbackLists.get(portId);
                if (null != cbLists) {
                    List<Callback1<MAC>> cbList = cbLists.remove(nwAddr);
                    if (null != cbList) {
                        for (Callback1<MAC> cb : cbList)
                            cb.call(null);
                    }
                }
            }
            // Else the expiration was rescheduled by an ARP reply arriving
            // while the entry was stale. Do nothing.
        }
    }

    private class ArpRetry implements Runnable {
        int nwAddr;
        UUID portId;
        String nwAddrStr;
        RouterPortConfig rtrPortConfig;

        ArpRetry(int nwAddr, UUID inPortId, RouterPortConfig rtrPortConfig) {
            this.nwAddr = nwAddr;
            nwAddrStr = IPv4.fromIPv4Address(nwAddr);
            this.portId = inPortId;
            this.rtrPortConfig = rtrPortConfig;
        }

        @Override
        public void run() {
            log.debug("ArpRetry.run: ip {} port {}", Net.convertIntAddressToString(nwAddr), portId);

            ArpCacheEntry entry = arpTable.get(new IntIPv4(nwAddr));
            if (null == entry) {
                // The entry has already been removed.
                log.debug("{} ARP retry triggered for {} but cache "
                        + "entry has already been removed", this, nwAddrStr);
                return;
            }
            if (null != entry.macAddr)
                // An answer arrived.
                return;
            // Don't retry if the ARP expired.
            if (entry.expiry <= reactor.currentTimeMillis())
                return;
            // Re-ARP and schedule again.
            log.debug("{} retry ARP request for {} on port {}", new Object[] {
                    this, nwAddrStr, portId });
            generateArpRequest(nwAddr, portId, rtrPortConfig);
            reactor.schedule(this, ARP_RETRY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private void generateArpRequest(int nwAddr, UUID portId,
                                    RouterPortConfig rtrPortConfig) {
        log.debug("generateArpRequest: ip {} port {}",
                  Net.convertIntAddressToString(nwAddr), portId);

        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte) 6);
        arp.setProtocolAddressLength((byte) 4);
        arp.setOpCode(ARP.OP_REQUEST);
        MAC portMac = rtrPortConfig.getHwAddr();
        arp.setSenderHardwareAddress(portMac);
        arp.setTargetHardwareAddress(MAC.fromString("00:00:00:00:00:00"));
        arp.setSenderProtocolAddress(
                IPv4.toIPv4AddressBytes(rtrPortConfig.portAddr));
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(nwAddr));
        Ethernet pkt = new Ethernet();
        pkt.setPayload(arp);
        pkt.setSourceMACAddress(portMac);
        pkt.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"));
        pkt.setEtherType(ARP.ETHERTYPE);

        // Now send it from the port.
        log.debug("generateArpRequest: sending {}", pkt);
        controller.addGeneratedPacket(pkt, portId);
        try {
            ArpCacheEntry entry = arpTable.get(new IntIPv4(nwAddr));
            long now = reactor.currentTimeMillis();
            if (null != entry) {
                // Modifying an uncloned entry confuses Directory.
                entry = entry.clone();
                entry.lastArp = now;
                arpTable.put(new IntIPv4(nwAddr), entry);
            }
        } catch (KeeperException e) {
            log.error("Got KeeperException trying to update lastArp", e);
        } catch (InterruptedException e) {
            log.error("Got InterruptedException trying to update lastArp", e);
        }
    }

    @Override
    public void freeFlowResources(OFMatch match, UUID inPortId) {
        // Do nothing. Resources used by the filters are freed elsewhere.
    }

    /**
     * Determine whether a packet can trigger an ICMP error.  Per RFC 1812 sec.
     * 4.3.2.7, some packets should not trigger ICMP errors:
     *   1) Other ICMP errors.
     *   2) Invalid IP packets.
     *   3) Destined to IP bcast or mcast address.
     *   4) Destined to a link-layer bcast or mcast.
     *   5) With source network prefix zero or invalid source.
     *   6) Second and later IP fragments.
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
    public boolean canSendICMP(Ethernet ethPkt, UUID egressPortId) {
        if (ethPkt.getEtherType() != IPv4.ETHERTYPE)
            return false;
        IPv4 ipPkt = IPv4.class.cast(ethPkt.getPayload());
        // Ignore ICMP errors.
        if (ipPkt.getProtocol() == ICMP.PROTOCOL_NUMBER) {
            ICMP icmpPkt = ICMP.class.cast(ipPkt.getPayload());
            if (icmpPkt.isError()) {
                log.debug("Not generating ICMP Unreachable for an ICMP "
                        + "error packet.");
                return false;
            }
        }
        // TODO(pino): check the IP packet's validity - RFC1812 sec. 5.2.2
        // Ignore packets to IP mcast addresses.
        if (ipPkt.isMcast()) {
            log.debug("Not generating ICMP Unreachable for packet to an IP "
                    + "multicast address.");
            return false;
        }
        // Ignore packets sent to the local-subnet IP broadcast address of the
        // intended egress port.
        if (null != egressPortId) {
            RouterPortConfig portConfig = portCache.get(
                    egressPortId, RouterPortConfig.class);
            if (null == portConfig) {
                log.error("Failed to get the egress port's config from ZK {}",
                        egressPortId);
                return false;
            }
            if (ipPkt.isSubnetBcast(portConfig.nwAddr, portConfig.nwLength)) {
                log.debug("Not generating ICMP Unreachable for packet to "
                        + "the subnet local broadcast address.");
                return false;
            }
        }
        // Ignore packets to Ethernet broadcast and multicast addresses.
        if (ethPkt.isMcast()) {
            log.debug("Not generating ICMP Unreachable for packet to "
                    + "Ethernet broadcast or multicast address.");
            return false;
        }
        // Ignore packets with source network prefix zero or invalid source.
        // TODO(pino): See RFC 1812 sec. 5.3.7
        if (ipPkt.getSourceAddress() == 0xffffffff
                || ipPkt.getDestinationAddress() == 0xffffffff) {
            log.debug("Not generating ICMP Unreachable for all-hosts broadcast "
                    + "packet");
            return false;
        }
        // TODO(pino): check this fragment offset
        // Ignore datagram fragments other than the first one.
        if (0 != (ipPkt.getFragmentOffset() & 0x1fff)) {
            log.debug("Not generating ICMP Unreachable for IP fragment packet");
            return false;
        }
        return true;
    }

    /**
     * Send an ICMP error message.
     *
     * @param fwdInfo
     *            The packet context of the packet that triggered the error.
     * @param unreachCode
     *            The ICMP error code of ICMP Unreachable sent by this method.
     */
    // TODO: Why is this "forLocalPkt"?  As opposed to ...?
    private void sendICMPforLocalPkt(
            ForwardInfo fwdInfo, UNREACH_CODE unreachCode) {
        // Check whether the original packet is allowed to trigger ICMP.
        // TODO(pino, abel): do we need the packet as seen by the ingress to
        // this router?
        if (!canSendICMP(fwdInfo.pktIn, fwdInfo.inPortId))
            return;
        // Build the ICMP packet from inside-out: ICMP, IPv4, Ethernet headers.
        ICMP icmp = new ICMP();
        icmp.setUnreachable(
                unreachCode, IPv4.class.cast(fwdInfo.pktIn.getPayload()));
        IPv4 ip = new IPv4();
        ip.setPayload(icmp);
        ip.setProtocol(ICMP.PROTOCOL_NUMBER);
        // The nwDst is the source of triggering IPv4 as seen by this router.
        ip.setDestinationAddress(fwdInfo.matchIn.getNetworkSource());
        // The nwSrc is the address of the ingress port.
        RouterPortConfig portConfig = portCache.get(
                fwdInfo.inPortId, RouterPortConfig.class);
        if (null == portConfig) {
            log.error("Failed to retrieve the inPort's configuration {}",
                    fwdInfo.inPortId);
            return;
        }
        ip.setSourceAddress(portConfig.portAddr);
        Ethernet eth = new Ethernet();
        eth.setPayload(ip);
        eth.setEtherType(IPv4.ETHERTYPE);
        eth.setSourceMACAddress(portConfig.getHwAddr());
        // The destination MAC is either the source of the original packet,
        // or the hwAddr of the gateway that sent us the packet.
        if (portConfig instanceof LogicalRouterPortConfig) {
            LogicalRouterPortConfig lcfg =
                    LogicalRouterPortConfig.class.cast(portConfig);
            // Use cfg.peer_uuid to get the peer port's configuration.
            PortConfig peerConfig = portCache.get(lcfg.peerId());
            if (null == peerConfig) {
                log.error("Failed to get the peer port's config from ZK.");
                return;
            }
            if (peerConfig instanceof LogicalRouterPortConfig)
                eth.setDestinationMACAddress(
                        ((LogicalRouterPortConfig) peerConfig).getHwAddr());
            else if (peerConfig instanceof LogicalBridgePortConfig)
                eth.setDestinationMACAddress(fwdInfo.pktIn.getSourceMACAddress());
            else
                throw new RuntimeException("Unrecognized peer port type.");
        } else if (portConfig instanceof MaterializedRouterPortConfig) {
            eth.setDestinationMACAddress((fwdInfo.pktIn.getSourceMACAddress()));
        }
        log.debug("sendICMPforLocalPkt from port {}, {} to {}", new Object[] {
                fwdInfo.inPortId,
                IPv4.fromIPv4Address(ip.getSourceAddress()),
                IPv4.fromIPv4Address(ip.getDestinationAddress()) });
        controller.addGeneratedPacket(eth, fwdInfo.inPortId);
    }
}
