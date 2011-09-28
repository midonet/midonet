package com.midokura.midolman.layer3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.actors.threadpool.Arrays;

import com.midokura.midolman.L3DevicePort;
import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.rules.RuleEngine;
import com.midokura.midolman.rules.RuleResult;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.util.Callback;
import com.midokura.midolman.util.Net;

/**
 * This class coordinates the routing logic for a single virtual router. It
 * delegates much of its internal logic to other class instances:
 * ReplicatedRoutingTable maintains the routing table and matches flows to
 * routes; RuleEngine maintains the rule chains and applies their logic to
 * packets in the router; NatLeaseManager (in RuleEngine) manages NAT mappings
 * and ip:port reservations.
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
public class Router {

    private static final Logger log = LoggerFactory.getLogger(Router.class);

    public static final long ARP_RETRY_MILLIS = 10 * 1000;
    public static final long ARP_TIMEOUT_MILLIS = 60 * 1000;
    public static final long ARP_EXPIRATION_MILLIS = 3600 * 1000;
    public static final long ARP_STALE_MILLIS = 1800 * 1000;

    public static final String PRE_ROUTING = "pre_routing";
    public static final String POST_ROUTING = "post_routing";

    public enum Action {
        BLACKHOLE, NOT_IPV4, NO_ROUTE, FORWARD, REJECT, CONSUMED;
    }

    /**
     * Clients of Router create and partially populate an instance of
     * ForwardInfo to call Router.process(fInfo). The Router populates a number
     * of field to indicate various decisions: the next action for the packet,
     * the next hop gateway address, the egress port, the packet at egress
     * (i.e. after possible modifications).
     */
    public static class ForwardInfo {
        // These fields are filled by the caller of Router.process():
        public UUID inPortId;
        public Ethernet pktIn;
        public MidoMatch matchIn;

        // These fields are filled by Router.process():
        public Action action;
        public UUID outPortId;
        public int gatewayNwAddr;
        public MidoMatch matchOut;
        public boolean trackConnection;
    }

    private static class ArpCacheEntry {
        byte[] macAddr;
        long expiry;
        long stale;
        long lastArp;

        public ArpCacheEntry(byte[] macAddr, long expiry, long stale,
                long lastArp) {
            super();
            this.macAddr = macAddr;
            this.expiry = expiry;
            this.stale = stale;
            this.lastArp = lastArp;
        }
    }

    /**
     * Router uses one instance of this class to get a callback when there are
     * changes to the routes of any local materialized ports. The callback
     * keeps mirrors those changes to the shared/replicated routing table.
     * of any 
     */
    private class PortListener implements L3DevicePort.Listener {
        @Override
        public void configChanged(UUID portId,
                PortDirectory.MaterializedRouterPortConfig old,
                PortDirectory.MaterializedRouterPortConfig current) {
            // Do nothing here. We get the non-routes configuration from the
            // L3DevicePort each time we use it.
        }

        @Override
        public void routesChanged(UUID portId, Collection<Route> added,
                Collection<Route> removed) {
            log.debug("routesChanged: added {} removed {}", added, removed);

            // The L3DevicePort keeps the routes up-to-date and notifies us
            // of changes so we can keep the replicated routing table in sync.
            for (Route rt : added) {
                log.debug("{} routesChanged adding {} to table", rtrIdStr, rt
                        .toString());
                try {
                    table.addRoute(rt);
                } catch (KeeperException e) {
                    log.warn("routesChanged", e);
                } catch (InterruptedException e) {
                    log.warn("routesChanged", e);
                }
            }
            for (Route rt : removed) {
                log.debug("{} routesChanged removing {} from table", rtrIdStr,
                        rt.toString());
                try {
                    table.deleteRoute(rt);
                } catch (KeeperException e) {
                    log.warn("routesChanged", e);
                } catch (InterruptedException e) {
                    log.warn("routesChanged", e);
                }
            }
        }
    }

    protected UUID routerId;
    private String rtrIdStr;
    protected RuleEngine ruleEngine;
    protected ReplicatedRoutingTable table;
    // Note that only materialized ports are tracked. Package visibility for
    // testing.
    Map<UUID, L3DevicePort> devicePorts;
    private PortListener portListener;
    private Map<UUID, Map<Integer, ArpCacheEntry>> arpCaches;
    private Map<UUID, Map<Integer, List<Callback<byte[]>>>> arpCallbackLists;
    private Reactor reactor;
    private LoadBalancer loadBalancer;

    public Router(UUID routerId, RuleEngine ruleEngine,
            ReplicatedRoutingTable table, Reactor reactor) {
        this.routerId = routerId;
        this.rtrIdStr = routerId.toString();
        this.ruleEngine = ruleEngine;
        this.table = table;
        this.devicePorts = new HashMap<UUID, L3DevicePort>();
        this.portListener = new PortListener();
        this.arpCaches = new HashMap<UUID, Map<Integer, ArpCacheEntry>>();
        this.arpCallbackLists = new HashMap<UUID, Map<Integer, List<Callback<byte[]>>>>();
        this.reactor = reactor;
        this.loadBalancer = new DummyLoadBalancer(table);
    }

    // This should only be called for materialized ports, not logical ports.
    public void addPort(L3DevicePort port) throws KeeperException,
            InterruptedException {
        devicePorts.put(port.getId(), port);
        log.debug("{} addPort {} with number {}", new Object[] { rtrIdStr,
                port.getId(), port.getNum() });
        port.addListener(portListener);
        for (Route rt : port.getVirtualConfig().getRoutes()) {
            log.debug("{} adding route {} to table", rtrIdStr, rt.toString());
            table.addRoute(rt);
        }
        arpCaches.put(port.getId(), new HashMap<Integer, ArpCacheEntry>());
        arpCallbackLists.put(port.getId(),
                new HashMap<Integer, List<Callback<byte[]>>>());
    }

    // This should only be called for materialized ports, not logical ports.
    public void removePort(L3DevicePort port) throws KeeperException,
            InterruptedException {
        devicePorts.remove(port.getId());
        log.debug("{} removePort {} with number", new Object[] { rtrIdStr,
                port.getId(), port.getNum() });
        port.removeListener(portListener);
        for (Route rt : port.getVirtualConfig().getRoutes()) {
            log.debug("{} removing route {} from table", rtrIdStr, rt
                    .toString());
            table.deleteRoute(rt);
        }
        arpCaches.remove(port.getId());
        arpCallbackLists.remove(port.getId());
    }

    public void getMacForIp(UUID portId, int nwAddr, Callback<byte[]> cb) {
        Map<Integer, ArpCacheEntry> arpCache = arpCaches.get(portId);
        L3DevicePort devPort = devicePorts.get(portId);
        String nwAddrStr = IPv4.fromIPv4Address(nwAddr);
        if (null == arpCache || null == devPort)
            throw new IllegalArgumentException(String.format("%s cannot get "
                    + "mac for %s on port %s - port not local.", rtrIdStr,
                    nwAddrStr, portId.toString()));
        // The nwAddr should be in the port's localNwAddr/localNwLength.
        int shift = 32 - devPort.getVirtualConfig().localNwLength;
        if ((nwAddr >>> shift) != (devPort.getVirtualConfig().localNwAddr >>> shift)) {
            log.warn("getMacForIp: {} cannot get mac for {} - address not in network "
                    + "segment of port {}", new Object[] { rtrIdStr, nwAddrStr,
                    portId.toString() });
            // TODO(pino): should this call be invoked asynchronously?
            cb.call(null);
            return;
        }
        ArpCacheEntry entry = arpCache.get(nwAddr);
        long now = reactor.currentTimeMillis();
        if (null != entry && null != entry.macAddr) {
            if (entry.stale < now && entry.lastArp + ARP_RETRY_MILLIS < now) {
                // Note that ARP-ing to refresh a stale entry doesn't retry.
                log.debug("getMacForIp: {} getMacForIp refreshing ARP cache entry for {}",
                        rtrIdStr, nwAddrStr);
                generateArpRequest(nwAddr, portId);
            }
            // TODO(pino): should this call be invoked asynchronously?
            cb.call(entry.macAddr);
            return;
        }
        // Generate an ARP if there isn't already one in flight and someone
        // is waiting for the reply. Note that there is one case when we
        // generate the ARP even if there's one in flight: when the previous
        // ARP was generated by a stale entry that was subsequently expired.
        Map<Integer, List<Callback<byte[]>>> cbLists = arpCallbackLists
                .get(portId);
        if (null == cbLists) {
            // This should never happen.
            log.error("getMacForIp: {} getMacForIp found null arpCallbacks map for port {} "
                    + "but arpCache was not null", rtrIdStr, portId.toString());
            cb.call(null);
        }
        List<Callback<byte[]>> cbList = cbLists.get(nwAddr);
        if (null == cbList) {
            cbList = new ArrayList<Callback<byte[]>>();
            cbLists.put(nwAddr, cbList);
            log.debug("getMacForIp: {} getMacForIp generating ARP request for {}", rtrIdStr,
                    nwAddrStr);
            generateArpRequest(nwAddr, portId);
            arpCache.put(nwAddr, new ArpCacheEntry(null, now
                    + ARP_TIMEOUT_MILLIS, now + ARP_RETRY_MILLIS, now));
            // Schedule ARP retry and expiration.
            reactor.schedule(new ArpRetry(nwAddr, portId), ARP_RETRY_MILLIS,
                    TimeUnit.MILLISECONDS);
            reactor.schedule(new ArpExpiration(nwAddr, portId),
                    ARP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
        cbList.add(cb);
    }

    public void process(ForwardInfo fwdInfo) {
        // Check if it's addressed to us (ARP, SNMP, ICMP Echo, ...)
        // Only handle ARP so far.
        if (fwdInfo.matchIn.getDataLayerType() == ARP.ETHERTYPE) {
            processArp(fwdInfo.pktIn, fwdInfo.inPortId);
            fwdInfo.action = Action.CONSUMED;
            return;
        }
        if (fwdInfo.matchIn.getDataLayerType() != IPv4.ETHERTYPE) {
            fwdInfo.action = Action.NOT_IPV4;
            return;
        }

        log.debug(
                "{} apply pre-routing rules on pkt from port {} from {} to "
                        + "{}",
                new Object[] {
                        rtrIdStr,
                        null == fwdInfo.inPortId ? "ICMP" : fwdInfo.inPortId
                                .toString(),
                        IPv4.fromIPv4Address(fwdInfo.matchIn.getNetworkSource()),
                        IPv4.fromIPv4Address(fwdInfo.matchIn
                                .getNetworkDestination()) });
        // Apply pre-routing rules. Clone the original match in order to avoid
        // changing it.
        RuleResult res = ruleEngine.applyChain(PRE_ROUTING, fwdInfo.matchIn
                .clone(), fwdInfo.inPortId, null);
        if (res.action.equals(RuleResult.Action.DROP)) {
            fwdInfo.action = Action.BLACKHOLE;
            return;
        }
        if (res.action.equals(RuleResult.Action.REJECT)) {
            fwdInfo.action = Action.REJECT;
            return;
        }
        if (!res.action.equals(RuleResult.Action.ACCEPT))
            throw new RuntimeException("Pre-routing returned an action other "
                    + "than ACCEPT, DROP or REJECT.");
        fwdInfo.trackConnection = res.trackConnection;

        log.debug("{} send pkt to routing table.", rtrIdStr);
        // Do a routing table lookup.
        Route rt = loadBalancer.lookup(res.match);
        if (null == rt) {
            fwdInfo.action = Action.NO_ROUTE;
            return;
        }
        if (rt.nextHop.equals(Route.NextHop.BLACKHOLE)) {
            fwdInfo.action = Action.BLACKHOLE;
            return;
        }
        if (rt.nextHop.equals(Route.NextHop.REJECT)) {
            fwdInfo.action = Action.REJECT;
            return;
        }
        if (!rt.nextHop.equals(Route.NextHop.PORT))
            throw new RuntimeException("Routing table returned next hop "
                    + "that isn't one of BLACKHOLE, NO_ROUTE, PORT or REJECT.");
        if (null == rt.nextHopPort) {
            log.error(
                    "{} route indicates forward to port, but no portId given",
                    rtrIdStr);
            // TODO(pino): should we remove this route?
            // For now just drop packets that match this route.
            fwdInfo.action = Action.BLACKHOLE;
            return;
        }

        log.debug("{} pkt next hop {} and egress port {} - applying "
                + "post-routing.", new Object[] { rtrIdStr,
                IPv4.fromIPv4Address(rt.nextHopGateway),
                rt.nextHopPort.toString() });
        // Apply post-routing rules.
        res = ruleEngine.applyChain(POST_ROUTING, res.match, fwdInfo.inPortId,
                rt.nextHopPort);
        if (res.action.equals(RuleResult.Action.DROP)) {
            fwdInfo.action = Action.BLACKHOLE;
            return;
        }
        if (res.action.equals(RuleResult.Action.REJECT)) {
            fwdInfo.action = Action.REJECT;
            return;
        }
        if (!res.action.equals(RuleResult.Action.ACCEPT))
            throw new RuntimeException("Post-routing returned an action other "
                    + "than ACCEPT, DROP or REJECT.");
        if (!fwdInfo.trackConnection && res.trackConnection)
            fwdInfo.trackConnection = true;

        fwdInfo.outPortId = rt.nextHopPort;
        fwdInfo.matchOut = res.match;
        fwdInfo.gatewayNwAddr = (0 == rt.nextHopGateway) ? res.match
                .getNetworkDestination() : rt.nextHopGateway;
        fwdInfo.action = Action.FORWARD;
        return;
    }

    private void processArp(Ethernet etherPkt, UUID inPortId) {
        if (!(etherPkt.getPayload() instanceof ARP)) {
            log.warn("{} ignoring packet with ARP ethertype but non-ARP "
                    + "payload.", rtrIdStr);
            return;
        }
        ARP arpPkt = (ARP) etherPkt.getPayload();
        // Discard the arp if its protocol type is not IP.
        if (arpPkt.getProtocolType() != ARP.PROTO_TYPE_IP) {
            log.warn("{} ignoring an ARP packet with protocol type {}",
                    rtrIdStr, arpPkt.getProtocolType());
            return;
        }
        L3DevicePort devPort = devicePorts.get(inPortId);
        if (null == devPort) {
            log.warn("{} ignoring an ARP on {} - port is not local.", rtrIdStr,
                    inPortId.toString());
            return;
        }
        if (arpPkt.getOpCode() == ARP.OP_REQUEST) {
            // ARP requests should broadcast or multicast. Ignore otherwise.
            if (!etherPkt.isMcast()) {
                log.warn("{} ignoring an ARP with a non-bcast/mcast dlDst {}",
                        rtrIdStr, Net.convertByteMacToString(etherPkt
                                .getDestinationMACAddress()));
                return;
            }
            processArpRequest(arpPkt, devPort);
        } else if (arpPkt.getOpCode() == ARP.OP_REPLY)
            processArpReply(arpPkt, devPort);

        // We ignore any other ARP packets: they may be malicious, bogus,
        // gratuitous ARP announcement requests, etc.
        // TODO(pino): above comment ported from Python, but I don't get it
        // since there are only two possible ARP operations. Was this meant to
        // go somewhere else?
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

    private void processArpRequest(ARP arpPkt, L3DevicePort devPortIn) {
        // If the request is for the ingress port's own address, it's for us.
        // Respond with the port's Mac address.
        // If the request is for an IP address which we emulate
        // switching for, we spoof the target host and respond to the ARP
        // with our own MAC address. These addresses are those in nw_prefix
        // but not local_nw_prefix for the in_port. (local_nw_prefix addresses
        // are assumed to be already handled by a switch.)

        // First get the ingress port's mac address
        PortDirectory.MaterializedRouterPortConfig portConfig = devPortIn
                .getVirtualConfig();
        int tpa = IPv4.toIPv4Address(arpPkt.getTargetProtocolAddress());
        if (tpa != devPortIn.getVirtualConfig().portAddr
                && !spoofL2Network(tpa, portConfig.nwAddr, portConfig.nwLength,
                        portConfig.localNwAddr, portConfig.localNwLength)) {
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
        byte[] portMac = devPortIn.getMacAddr();
        arp.setSenderHardwareAddress(portMac);
        arp.setSenderProtocolAddress(arpPkt.getTargetProtocolAddress());
        arp.setTargetHardwareAddress(arpPkt.getSenderHardwareAddress());
        arp.setTargetProtocolAddress(arpPkt.getSenderProtocolAddress());
        int spa = IPv4.toIPv4Address(arpPkt.getSenderProtocolAddress());
        log.debug("{} replying to ARP request from {} for {} with own mac {}",
                new Object[] { rtrIdStr, IPv4.fromIPv4Address(spa),
                        IPv4.fromIPv4Address(tpa),
                        Net.convertByteMacToString(portMac) });
        // TODO(pino) logging.
        Ethernet pkt = new Ethernet();
        pkt.setPayload(arp);
        pkt.setSourceMACAddress(portMac);
        pkt.setDestinationMACAddress(arpPkt.getSenderHardwareAddress());
        pkt.setEtherType(ARP.ETHERTYPE);
        // Now send it from the port.
        devPortIn.send(pkt.serialize());
    }

    private void processArpReply(ARP arpPkt, L3DevicePort devPortIn) {
        // Verify that the reply was meant for us: tpa is the port's nw addr,
        // and tha is the port's mac.
        // TODO(pino): only a suggestion in the Python, I implemented it. OK?
        UUID inPortId = devPortIn.getId();
        PortDirectory.MaterializedRouterPortConfig portConfig = devPortIn
                .getVirtualConfig();
        int tpa = IPv4.toIPv4Address(arpPkt.getTargetProtocolAddress());
        byte[] tha = arpPkt.getTargetHardwareAddress();
        if (tpa != portConfig.portAddr
                || !Arrays.equals(tha, devPortIn.getMacAddr())) {
            log.debug("{} ignoring ARP reply because its tpa or tha don't "
                    + "match ip addr or mac of port {}", rtrIdStr, inPortId
                    .toString());
            return;
        }
        // Question: Should we make noise if an ARP reply disagrees with the
        // existing arp_cache entry?

        Map<Integer, ArpCacheEntry> arpCache = arpCaches.get(inPortId);
        if (null == arpCache) {
            log.error("{} ignoring ARP reply - could not find ARP cache for "
                    + "local port {}", rtrIdStr, inPortId.toString());
            return;
        }

        byte[] sha = arpPkt.getSenderHardwareAddress();
        int spa = IPv4.toIPv4Address(arpPkt.getSenderProtocolAddress());
        log.debug("{} received an ARP reply with spa {} and sha {}",
                new Object[] { rtrIdStr, IPv4.fromIPv4Address(spa),
                        Net.convertByteMacToString(sha) });
        long now = reactor.currentTimeMillis();
        ArpCacheEntry entry = new ArpCacheEntry(sha, now
                + ARP_EXPIRATION_MILLIS, now + ARP_STALE_MILLIS, 0);
        arpCache.put(spa, entry);
        reactor.schedule(new ArpExpiration(spa, inPortId),
                ARP_EXPIRATION_MILLIS, TimeUnit.MILLISECONDS);
        Map<Integer, List<Callback<byte[]>>> cbLists = arpCallbackLists
                .get(inPortId);
        if (null == cbLists)
            return;
        List<Callback<byte[]>> cbList = cbLists.remove(spa);
        if (null != cbList)
            for (Callback<byte[]> cb : cbList)
                cb.call(sha);
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
            Map<Integer, ArpCacheEntry> arpCache = arpCaches.get(portId);
            String nwAddrStr = IPv4.fromIPv4Address(nwAddr);
            if (null == arpCache) {
                // ARP cache is gone, probably because port went down.
                log.debug("{} ARP expiration triggered for {} but port {} has "
                        + "no ARP cache - port was removed?", new Object[] {
                        rtrIdStr, nwAddrStr, portId.toString() });
                return;
            }
            ArpCacheEntry entry = arpCache.get(nwAddr);
            if (null == entry) {
                // The entry has already been removed.
                log.debug("{} ARP expiration triggered for {} but cache "
                        + "entry has already been removed", rtrIdStr, nwAddrStr);
                return;
            }
            if (entry.expiry <= reactor.currentTimeMillis()) {
                log.debug("{} expiring ARP cache entry for {}", rtrIdStr,
                        nwAddrStr);
                arpCache.remove(nwAddr);
                Map<Integer, List<Callback<byte[]>>> cbLists = arpCallbackLists
                        .get(portId);
                if (null != cbLists) {
                    List<Callback<byte[]>> cbList = cbLists.remove(nwAddr);
                    if (null != cbList) {
                        for (Callback<byte[]> cb : cbList)
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

        ArpRetry(int nwAddr, UUID inPortId) {
            this.nwAddr = nwAddr;
            nwAddrStr = IPv4.fromIPv4Address(nwAddr);
            this.portId = inPortId;
        }

        @Override
        public void run() {
            Map<Integer, ArpCacheEntry> arpCache = arpCaches.get(portId);
            if (null == arpCache) {
                // ARP cache is gone, probably because port went down.
                log.debug("{} ARP retry triggered for {} but port {} has "
                        + "no ARP cache - port was removed?", new Object[] {
                        rtrIdStr, nwAddrStr, portId.toString() });
                return;
            }
            ArpCacheEntry entry = arpCache.get(nwAddr);
            if (null == entry) {
                // The entry has already been removed.
                log.debug("{} ARP retry triggered for {} but cache "
                        + "entry has already been removed", rtrIdStr, nwAddrStr);
                return;
            }
            if (null != entry.macAddr)
                // An answer arrived.
                return;
            // Re-ARP and schedule again.
            log.debug("{} retry ARP request for {} on port {}", new Object[] {
                    rtrIdStr, nwAddrStr, portId.toString() });
            generateArpRequest(nwAddr, portId);
            reactor.schedule(this, ARP_RETRY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private void generateArpRequest(int nwAddr, UUID portId) {
        L3DevicePort devPort = devicePorts.get(portId);
        if (null == devPort) {
            log.warn("{} generateArpRequest could not find device port for "
                    + "{} - was port removed?", rtrIdStr, portId.toString());
            return;
        }
        PortDirectory.MaterializedRouterPortConfig portConfig = devPort
                .getVirtualConfig();
        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte) 6);
        arp.setProtocolAddressLength((byte) 4);
        arp.setOpCode(ARP.OP_REQUEST);
        byte[] portMac = devPort.getMacAddr();
        arp.setSenderHardwareAddress(portMac);
        arp.setTargetHardwareAddress(Ethernet.toMACAddress("00:00:00:00:00:00"));
        arp.setSenderProtocolAddress(IPv4
                .toIPv4AddressBytes(portConfig.portAddr));
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(nwAddr));
        Ethernet pkt = new Ethernet();
        pkt.setPayload(arp);
        pkt.setSourceMACAddress(portMac);
        byte b = (byte) 0xff;
        pkt.setDestinationMACAddress(new byte[] { b, b, b, b, b, b });
        pkt.setEtherType(ARP.ETHERTYPE);
        // Now send it from the port.
        devPort.send(pkt.serialize());
        Map<Integer, ArpCacheEntry> arpCache = arpCaches.get(portId);
        ArpCacheEntry entry = arpCache.get(nwAddr);
        long now = reactor.currentTimeMillis();
        if (null != entry)
            entry.lastArp = now;
    }
}
