package com.midokura.midolman.layer3;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.KeeperException;

import com.midokura.midolman.L3DevicePort;
import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.rules.RuleEngine;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;

public class Router {

    public static final long ARP_RETRY_MILLIS = 10000;
    public static final long ARP_TIMEOUT_MILLIS = 60000;
    public static final long ARP_EXPIRATION_MILLIS = 10000;
    public static final long ARP_STALE_MILLIS = 1800;

    public enum Action {
        BLACKHOLE, NOT_IPV4, NO_ROUTE, FORWARD, REJECT, CONSUMED;
    }

    public static class ForwardInfo {
        UUID outPortId;
        int gatewayNwAddr;
        MidoMatch newMatch;
        boolean trackConnection;
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
                table.addRoute(rt);
            }
            for (Route rt : removed) {
                table.deleteRoute(rt);
            }
        }
    }

    protected UUID routerId;
    private RuleEngine ruleEngine;
    private RoutingTable table;
    private PortDirectory portDir;
    // Note that only materialized ports are tracked.
    private Map<UUID, L3DevicePort> devicePorts;
    private PortListener portListener;
    private Map<UUID, Map<Integer, ArpCacheEntry>> arpCaches;
    private Reactor reactor;

    public Router(UUID routerId, RuleEngine ruleEngine, RoutingTable table,
            PortDirectory portDir, Reactor reactor) {
        this.routerId = routerId;
        this.ruleEngine = ruleEngine;
        this.table = table;
        this.portDir = portDir;
        this.devicePorts = new HashMap<UUID, L3DevicePort>();
        this.portListener = new PortListener();
        this.arpCaches = new HashMap<UUID, Map<Integer, ArpCacheEntry>>();
        this.reactor = reactor;
    }

    // This should only be called for materialized ports, not logical ports.
    void addPort(L3DevicePort port) throws KeeperException,
            InterruptedException {
        devicePorts.put(port.getId(), port);
        port.addListener(portListener);
        for (Route rt : port.getVirtualConfig().routes) {
            table.addRoute(rt);
        }
    }

    // This should only be called for materialized ports, not logical ports.
    void removePort(L3DevicePort port) throws KeeperException,
            InterruptedException {
        devicePorts.remove(port.getId());
        port.removeListener(portListener);
        for (Route rt : port.getVirtualConfig().routes) {
            table.deleteRoute(rt);
        }
    }

    public Future getMacForIp(UUID outPort, int nwAddr,
            Runnable completionCallback) {
        return null;
    }

    public Action process(MidoMatch pktMatch, byte[] packet, UUID inPortId,
            ForwardInfo fwdInfo) {
        // TODO Auto-generated method stub
        return null;
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
        UUID inPortId;

        ArpExpiration(int nwAddr, UUID inPortId) {
            this.nwAddr = nwAddr;
            this.inPortId = inPortId;
        }

        @Override
        public void run() {
            // TODO Auto-generated method stub
        }
    }

}
