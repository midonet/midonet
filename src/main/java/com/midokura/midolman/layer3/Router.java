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

import com.midokura.midolman.L3DevicePort;
import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.rules.RuleEngine;
import com.midokura.midolman.rules.RuleResult;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.util.Callback;

public class Router {

    private static final Logger log = LoggerFactory.getLogger(Router.class);

    public static final long ARP_RETRY_MILLIS = 10000;
    public static final long ARP_TIMEOUT_MILLIS = 60000;
    public static final long ARP_EXPIRATION_MILLIS = 10000;
    public static final long ARP_STALE_MILLIS = 1800;

    public enum Action {
        BLACKHOLE, NOT_IPV4, NO_ROUTE, FORWARD, REJECT, CONSUMED;
    }

    public static class ForwardInfo {
        public Action action;
        public UUID inPortId;
        public UUID outPortId;
        public int gatewayNwAddr;
        public MidoMatch newMatch;
        public boolean trackConnection;
        public Ethernet pktIn;
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
                MaterializedRouterPortConfig old,
                MaterializedRouterPortConfig current) {
        }

        @Override
        public void routesChanged(UUID portId, Collection<Route> added,
                Collection<Route> removed) {
            for (Route rt : added) {
                try {
                    table.addRoute(rt);
                } catch (KeeperException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
            for (Route rt : removed) {
                try {
                    table.deleteRoute(rt);
                } catch (KeeperException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
    }

    protected UUID routerId;
    private RuleEngine ruleEngine;
    private ReplicatedRoutingTable table;
    private PortDirectory portDir;
    // Note that only materialized ports are tracked.
    private Map<UUID, L3DevicePort> devicePorts;
    private PortListener portListener;
    private Map<UUID, Map<Integer, ArpCacheEntry>> arpCaches;
    private Map<UUID, Map<Integer, List<Callback<byte[]>>>> arpCallbackLists;
    private Reactor reactor;
    private LoadBalancer loadBalancer;

    public Router(UUID routerId, RuleEngine ruleEngine,
            ReplicatedRoutingTable table, PortDirectory portDir, Reactor reactor) {
        this.routerId = routerId;
        this.ruleEngine = ruleEngine;
        this.table = table;
        this.portDir = portDir;
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
        for (Route rt : port.getVirtualConfig().routes) {
            table.addRoute(rt);
        }
    }

    // This should only be called for materialized ports, not logical ports.
    public void removePort(L3DevicePort port) throws KeeperException,
            InterruptedException {
        devicePorts.remove(port.getId());
        port.removeListener(portListener);
        for (Route rt : port.getVirtualConfig().routes) {
            table.deleteRoute(rt);
        }
    }

    public byte[] getMacForIp(UUID portId, int nwAddr, Callback<byte[]> cb) {
        Map<Integer, ArpCacheEntry> arpCache = arpCaches.get(portId);
        ArpCacheEntry entry = arpCache.get(nwAddr);
        long now = System.currentTimeMillis();
        if (null != entry && null != entry.macAddr) {
            if (entry.stale < now && entry.lastArp + ARP_RETRY_MILLIS < now)
                // Entry is stale and it's been at least ARP_RETRY_MILLIS since
                // the last ARP was sent for it: re-ARP.
                generateArpRequest(nwAddr, portId);
            return entry.macAddr;
        }
        // Store the callback. ARP for nwAddr's MAC if no outstanding ARP yet.
        Map<Integer, List<Callback<byte[]>>> cbLists = arpCallbackLists
                .get(portId);
        List<Callback<byte[]>> cbList = cbLists.get(nwAddr);
        if (null == cbList) {
            cbList = new ArrayList<Callback<byte[]>>();
            cbLists.put(nwAddr, cbList);
            generateArpRequest(nwAddr, portId);
            // Schedule ARP retry and expiration.
            reactor.schedule(new ArpRetry(nwAddr, portId), ARP_RETRY_MILLIS,
                    TimeUnit.MILLISECONDS);
            reactor.schedule(new ArpExpiration(nwAddr, portId),
                    ARP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
        cbList.add(cb);
        return null;
    }

    public void process(MidoMatch pktMatch, Ethernet ethPkt,
            ForwardInfo fwdInfo) {
        // Check if it's addressed to us (ARP, SNMP, ICMP Echo, ...)
        // Only handle ARP so far.
        if (pktMatch.getDataLayerType() == ARP.ETHERTYPE) {
            processArp(ethPkt, fwdInfo.inPortId);
            fwdInfo.action = Action.CONSUMED;
            return;
        }
        if (pktMatch.getDataLayerType() != IPv4.ETHERTYPE) {
            fwdInfo.action = Action.NOT_IPV4;
            return;
        }

        // Apply pre-routing rules.
        RuleResult res = ruleEngine.applyChain("pre_routing", pktMatch,
                fwdInfo.inPortId, null);
        if (res.action.equals(RuleResult.Action.DROP)) {
            fwdInfo.action = Action.BLACKHOLE;
            return;
        }
        if (res.action.equals(RuleResult.Action.REJECT)){
            fwdInfo.action = Action.REJECT;
            return;
        }
        if (res.action.equals(RuleResult.Action.ACCEPT))
            throw new RuntimeException("Pre-routing returned an action other "
                    + "than ACCEPT, DROP or REJECT.");
        fwdInfo.trackConnection = res.trackConnection;

        // Do a routing table lookup.
        Route rt = loadBalancer.lookup(pktMatch);
        if (null == rt) {
            fwdInfo.action = Action.NO_ROUTE;
            return;
        }
        if (rt.nextHop.equals(Route.NextHop.BLACKHOLE)){
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
        res = ruleEngine.applyChain("post_routing", res.match,
                fwdInfo.inPortId, rt.nextHopPort);
        if (res.action.equals(RuleResult.Action.DROP)){
            fwdInfo.action = Action.BLACKHOLE;
            return;
        }
        if (res.action.equals(RuleResult.Action.REJECT)){
            fwdInfo.action = Action.REJECT;
            return;
        }
        if (res.action.equals(RuleResult.Action.ACCEPT))
            throw new RuntimeException("Post-routing returned an action other "
                    + "than ACCEPT, DROP or REJECT.");
        if (!fwdInfo.trackConnection && res.trackConnection)
            fwdInfo.trackConnection = true;

        fwdInfo.outPortId = rt.nextHopPort;
        fwdInfo.newMatch = res.match;
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
            // ARP requests should be broadcast or multicast. Ignore otherwise.
            if (0 != (etherPkt.getDestinationMACAddress()[0] & 0x01)) {
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
            inNw = 0 == ((hostNwAddr >>> shift) ^ (nwAddr >>> shift));
        if (!inNw)
            return false;
        shift = 32 - localNwLength;
        inNw = true;
        if (32 > shift)
            inNw = 0 == ((hostNwAddr >>> shift) ^ (localNwAddr >>> shift));
        return !inNw;
    }

    private static int bytesToNwAddr(byte[] bytes) {
        if (bytes.length < 4)
            return 0;
        int addr = 0;
        for (int i = 0; i < 4; i++) {
            addr |= (bytes[i] & 0xff) << 8 * (3 - i);
        }
        return addr;
    }

    private static byte[] nwAddrToBytes(int addr) {
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) ((addr >>> (3 - i) * 8) & 0xff);
        }
        return bytes;
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
        MaterializedRouterPortConfig portConfig = devPort.getVirtualConfig();
        int tpa = bytesToNwAddr(arpPkt.getTargetProtocolAddress());
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
        MaterializedRouterPortConfig portConfig = devPort.getVirtualConfig();
        int tpa = bytesToNwAddr(arpPkt.getTargetProtocolAddress());
        byte[] tha = arpPkt.getTargetHardwareAddress();
        if (tpa != portConfig.portAddr || !tha.equals(devPort.getMacAddr()))
            return;

        // Question: Should we make noise if an ARP reply disagrees with the
        // existing arp_cache entry?
        Map<Integer, ArpCacheEntry> arpCache = arpCaches.get(inPortId);
        if (null == arpCache)
            return;

        long now = System.currentTimeMillis();
        ArpCacheEntry entry = new ArpCacheEntry(arpPkt
                .getSenderHardwareAddress(), now + ARP_EXPIRATION_MILLIS, now
                + ARP_STALE_MILLIS, 0);
        int spa = bytesToNwAddr(arpPkt.getSenderProtocolAddress());
        arpCache.put(spa, entry);
        reactor.schedule(new ArpExpiration(spa, inPortId),
                ARP_EXPIRATION_MILLIS, TimeUnit.MILLISECONDS);
        // logging.debug('router_fe: received an arp reply on port %s for ip %s;
        // '
        // 'mac is %s' % (in_port, socket.inet_ntoa(packet.spa),
        // ieee_802.mac_to_str(packet.sha)))

        // Complete any pending getMacForIp calls.
        /*
         * callback_list = self._pending_arp_callbacks.get( (packet.spa,
         * in_port) ) if callback_list is not None: for callback in
         * callback_list: callback(packet.sha) del
         * self._pending_arp_callbacks[(packet.spa, in_port)]
         */
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
            if (entry.expiry <= System.currentTimeMillis()) {
                arpCache.remove(nwAddr);
                // TODO(pino): complete callbacks waiting for this ARP.
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
        MaterializedRouterPortConfig portConfig = devPort.getVirtualConfig();
        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte) 6);
        arp.setProtocolAddressLength((byte) 4);
        arp.setOpCode(ARP.OP_REQUEST);
        byte[] portMac = devPort.getMacAddr();
        arp.setSenderHardwareAddress(portMac);
        arp.setSenderProtocolAddress(nwAddrToBytes(portConfig.portAddr));
        arp.setTargetProtocolAddress(nwAddrToBytes(nwAddr));
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
        long now = System.currentTimeMillis();
        if (null == entry)
            arpCache.put(nwAddr, new ArpCacheEntry(null, now
                    + ARP_TIMEOUT_MILLIS, now + ARP_STALE_MILLIS, now));
        else
            entry.lastArp = now;
    }
}
