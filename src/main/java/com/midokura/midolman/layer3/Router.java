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

/**
 * This class coordinates the routing logic for a single virtual router. It uses
 * an instance of ReplicatedRoutingTable and an instance of RuleEngine to
 * delegate matching the best route and applying pre- and post-routing filtering
 * and nat rules.
 * 
 * 
 * @author pino
 * 
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

    public static class ForwardInfo {
        public Action action;
        public UUID inPortId;
        public UUID outPortId;
        public int gatewayNwAddr;
        public MidoMatch matchOut;
        public boolean trackConnection;
        public Ethernet pktIn;
        public MidoMatch matchIn;
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

    private class PortListener implements L3DevicePort.Listener {
        @Override
        public void configChanged(UUID portId,
                PortDirectory.MaterializedRouterPortConfig old,
                PortDirectory.MaterializedRouterPortConfig current) {
        }

        @Override
        public void routesChanged(UUID portId, Collection<Route> added,
                Collection<Route> removed) {
            for (Route rt : added) {
                try {
                    table.addRoute(rt);
                } catch (KeeperException e) {
                    log.warn("routesChanged", e);
                } catch (InterruptedException e) {
                    log.warn("routesChanged", e);
                }
            }
            for (Route rt : removed) {
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
        port.addListener(portListener);
        for (Route rt : port.getVirtualConfig().getRoutes()) {
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
        port.removeListener(portListener);
        for (Route rt : port.getVirtualConfig().getRoutes()) {
            table.deleteRoute(rt);
        }
        arpCaches.remove(port.getId());
        arpCallbackLists.remove(port.getId());
    }

    public void getMacForIp(UUID portId, int nwAddr, Callback<byte[]> cb) {
        Map<Integer, ArpCacheEntry> arpCache = arpCaches.get(portId);
        L3DevicePort devPort = devicePorts.get(portId);
        if (null == arpCache || null == devPort)
            throw new IllegalArgumentException("Cannot get mac for address "
                    + "on a port that is not local to this controller");
        // Check that the nwAddr to resolve is in the port's localNwAddr/
        // localNwLength?
        int shift = 32 - devPort.getVirtualConfig().localNwLength;
        if ((nwAddr >>> shift) != (devPort.getVirtualConfig().localNwAddr >>> shift))
            throw new IllegalArgumentException("Cannot get mac for address "
                    + "that is not in the port's local network segment.");
        ArpCacheEntry entry = arpCache.get(nwAddr);
        long now = reactor.currentTimeMillis();
        if (null != entry && null != entry.macAddr) {
            if (entry.stale < now && entry.lastArp + ARP_RETRY_MILLIS < now)
                // Note that ARP-ing to refresh a stale entry doesn't retry.
                generateArpRequest(nwAddr, portId);
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
        if (null == cbLists)
            throw new IllegalArgumentException("Cannot get mac for a port "
                    + "that is not local to this controller");
        List<Callback<byte[]>> cbList = cbLists.get(nwAddr);
        if (null == cbList) {
            cbList = new ArrayList<Callback<byte[]>>();
            cbLists.put(nwAddr, cbList);
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
        if (null == rt.nextHopPort)
            // TODO(pino): malformed rule. Should we silently drop the packet?
            throw new RuntimeException("Routing table returned next hop PORT "
                    + "but no port UUID given.");
        // TODO(pino): log next hop portId and gateway addr..

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
        if (!(etherPkt.getPayload() instanceof ARP))
            return;
        ARP arpPkt = (ARP) etherPkt.getPayload();
        // Discard the arp if its protocol type is not IP.
        if (arpPkt.getProtocolType() != ARP.PROTO_TYPE_IP)
            return;
        if (arpPkt.getOpCode() == ARP.OP_REQUEST) {
            // ARP requests should broadcast or multicast. Ignore otherwise.
            if (!etherPkt.isMcast()) {
                // TODO(pino): logging.debug("Non-multicast/broadcast ARP
                // request to ...",
                return;
            }
            processArpRequest(arpPkt, inPortId);
        } else if (arpPkt.getOpCode() == ARP.OP_REPLY)
            processArpReply(arpPkt, inPortId);
        else
            // TODO(pino) log gratuitous arp packet.
            // We ignore any other ARP packets: they may be malicious, bogus,
            // gratuitous ARP announcement requests, etc.
            return;
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

    private void processArpRequest(ARP arpPkt, UUID inPortId) {
        // If the request is for the ingress port's own address, it's for us.
        // Respond with the port's Mac address.
        // If the request is for an IP address which we emulate
        // switching for, we spoof the target host and respond to the ARP
        // with our own MAC address. These addresses are those in nw_prefix
        // but not local_nw_prefix for the in_port. (local_nw_prefix addresses
        // are assumed to be already handled by a switch.)

        // First get the ingress port's mac address
        L3DevicePort devPort = devicePorts.get(inPortId);
        PortDirectory.MaterializedRouterPortConfig portConfig = devPort.getVirtualConfig();
        int tpa = IPv4.toIPv4Address(arpPkt.getTargetProtocolAddress());
        if (tpa != devPort.getVirtualConfig().portAddr
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
        byte[] portMac = devPort.getMacAddr();
        arp.setSenderHardwareAddress(portMac);
        arp.setSenderProtocolAddress(arpPkt.getTargetProtocolAddress());
        arp.setTargetHardwareAddress(arpPkt.getSenderHardwareAddress());
        arp.setTargetProtocolAddress(arpPkt.getSenderProtocolAddress());
        // TODO(pino) logging.
        Ethernet pkt = new Ethernet();
        pkt.setPayload(arp);
        pkt.setSourceMACAddress(portMac);
        pkt.setDestinationMACAddress(arpPkt.getSenderHardwareAddress());
        pkt.setEtherType(ARP.ETHERTYPE);
        // Now send it from the port.
        devPort.send(pkt.serialize());
    }

    private void processArpReply(ARP arpPkt, UUID inPortId) {
        // Verify that the reply was meant for us.
        L3DevicePort devPort = devicePorts.get(inPortId);
        PortDirectory.MaterializedRouterPortConfig portConfig = devPort.getVirtualConfig();
        int tpa = IPv4.toIPv4Address(arpPkt.getTargetProtocolAddress());
        byte[] tha = arpPkt.getTargetHardwareAddress();
        if (tpa != portConfig.portAddr
                || !Arrays.equals(tha, devPort.getMacAddr()))
            return;

        // Question: Should we make noise if an ARP reply disagrees with the
        // existing arp_cache entry?
        Map<Integer, ArpCacheEntry> arpCache = arpCaches.get(inPortId);
        if (null == arpCache)
            return;

        long now = reactor.currentTimeMillis();
        byte[] sha = arpPkt.getSenderHardwareAddress();
        ArpCacheEntry entry = new ArpCacheEntry(sha, now
                + ARP_EXPIRATION_MILLIS, now + ARP_STALE_MILLIS, 0);
        int spa = IPv4.toIPv4Address(arpPkt.getSenderProtocolAddress());
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
            if (null == arpCache)
                // ARP cache is gone, probably because port went down.
                return;
            ArpCacheEntry entry = arpCache.get(nwAddr);
            if (null == entry)
                // The entry has already been removed.
                return;
            if (entry.expiry <= reactor.currentTimeMillis()) {
                arpCache.remove(nwAddr);
                Map<Integer, List<Callback<byte[]>>> cbLists = arpCallbackLists
                        .get(portId);
                if (null != cbLists) {
                    List<Callback<byte[]>> cbList = cbLists.get(nwAddr);
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

        ArpRetry(int nwAddr, UUID inPortId) {
            this.nwAddr = nwAddr;
            this.portId = inPortId;
        }

        @Override
        public void run() {
            Map<Integer, ArpCacheEntry> arpCache = arpCaches.get(portId);
            if (null == arpCache)
                // ARP cache is gone, probably because port went down.
                return;
            ArpCacheEntry entry = arpCache.get(nwAddr);
            if (null == entry)
                // The entry has already been removed.
                return;
            if (null != entry.macAddr)
                // An answer arrived.
                return;
            // Re-ARP and schedule again.
            generateArpRequest(nwAddr, portId);
            reactor.schedule(this, ARP_RETRY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private void generateArpRequest(int nwAddr, UUID portId) {
        L3DevicePort devPort = devicePorts.get(portId);
        if (null == devPort)
            return;
        PortDirectory.MaterializedRouterPortConfig portConfig = devPort.getVirtualConfig();
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
        // TODO(pino) add logging.
        // Now send it from the port.
        devPort.send(pkt.serialize());
        Map<Integer, ArpCacheEntry> arpCache = arpCaches.get(portId);
        ArpCacheEntry entry = arpCache.get(nwAddr);
        long now = reactor.currentTimeMillis();
        if (null != entry)
            entry.lastArp = now;
    }
}
