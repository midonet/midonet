/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPortStatus;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.packets.IntIPv4;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.test.Bridge;
import com.midokura.midolman.test.BridgePort;
import com.midokura.midolman.test.Host;
import com.midokura.midolman.test.Router;
import com.midokura.midolman.test.RouterPort;
import com.midokura.midolman.test.VirtualNetwork;
import com.midokura.cache.Cache;
import com.midokura.midolman.util.MockCache;


import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.MatcherAssert.assertThat;

public class TestFlowInvalidation {
    Host host1;
    Host host2;
    Bridge router1bridge1;
    Bridge router1bridge2;
    Bridge router2bridge1;
    Bridge router2bridge2;
    Router router1;
    Router router2;
    Router provRouter;
    VirtualNetwork network;
    Cache cache = new MockCache();

    @BeforeMethod
    public void setUp() throws Exception {
        network = new VirtualNetwork();

        /* Set up:
         * 1) A ProviderRouter with an up-link and two down-links
         * 2) On each provider down-link, a TenantRouter.
         * 3) Each TenantRouter has two down-links to Bridges.
         * 4) VMs on the first Bridge on each TenantRouter are SNATed.
         * 5) VMs on the second Bridge on each TenantRouter have Floating IPs.
         */
        provRouter = network.makeRouter();
        RouterPort provUplink = provRouter.addPort("uplink", "10.0.0.1", 30);
        provUplink.addRoute("0.0.0.0", 0, "10.0.0.2");

        router1 = network.makeRouter();
        provRouter.linkRouter("portToTenant1", router1, "uplink");
        provRouter.getPort("portToTenant1").addRoute("112.0.0.0", 28,
                router1.getPort("uplink").getAddress().toString());
        router1.getPort("uplink").addRoute("0.0.0.0", 0,
                provRouter.getPort("portToTenant1").getAddress().toString());

        router2 = network.makeRouter();
        provRouter.linkRouter("portToTenant2", router2, "uplink");
        provRouter.getPort("portToTenant2").addRoute("112.0.0.16", 28,
                router2.getPort("uplink").getAddress().toString());
        router2.getPort("uplink").addRoute("0.0.0.0", 0,
                provRouter.getPort("portToTenant2").getAddress().toString());

        router1bridge1 = network.makeBridge();
        router1.linkBridge(
                "portToBridge1", "10.0.1.1", router1bridge1, "uplink", 100);
        router1.getPort("portToBridge1").addRoute("10.0.1.0", 24, null);
        // Add a SNAT for this bridge.
        router1.addSNAT("10.0.1.0", 24, "uplink", "112.0.0.15", (byte)0);
        router1bridge1.addPorts(0, 4);

        router1bridge2 = network.makeBridge();
        router1.linkBridge(
                "portToBridge2", "10.0.2.1", router1bridge2, "uplink", 100);
        router1.getPort("portToBridge2").addRoute("10.0.2.0", 24, null);
        // Add some floating IPs for VMs on this bridge.
        router1.addFloatingIP("10.0.2.2", "uplink", "112.0.0.2");
        router1.addFloatingIP("10.0.2.3", "uplink", "112.0.0.3");
        router1.addFloatingIP("10.0.2.4", "uplink", "112.0.0.4");
        router1.addFloatingIP("10.0.2.5", "uplink", "112.0.0.5");
        router1bridge2.addPorts(0, 4);

        router2bridge1 = network.makeBridge();
        router2.linkBridge(
                "portToBridge1", "10.0.1.1", router2bridge1, "uplink", 100);
        router2.getPort("portToBridge1").addRoute("10.0.1.0", 24, null);
        // Add a SNAT for this bridge.
        router2.addSNAT("10.0.1.0", 24, "uplink", "112.0.0.31", (byte)0);
        router2bridge1.addPorts(0, 4);

        router2bridge2 = network.makeBridge();
        router2.linkBridge(
                "portToBridge2", "10.0.2.1", router2bridge2, "uplink", 100);
        router2.getPort("portToBridge2").addRoute("10.0.2.0", 24, null);
        // Add some floating IPs for VMs on this bridge.
        router2.addFloatingIP("10.0.2.2", "uplink", "112.0.0.22");
        router2.addFloatingIP("10.0.2.3", "uplink", "112.0.0.23");
        router2.addFloatingIP("10.0.2.4", "uplink", "112.0.0.24");
        router2.addFloatingIP("10.0.2.5", "uplink", "112.0.0.25");
        router2bridge2.addPorts(0, 4);

        // Create a host with these materialized ports:
        // Router1Bridge1's ports
        // Router1Bridge2's ports
        IntIPv4 hostAddr1 = IntIPv4.fromString("192.168.0.10");
        host1 = new Host(network.getBaseDirectory(), network.getBasePath(),
                hostAddr1, cache);
        short i = 100;
        List<BridgePort> ports = new ArrayList<BridgePort>();
        ports.addAll(router1bridge1.getPorts());
        ports.addAll(router1bridge2.getPorts());
        for (BridgePort port : ports) {
            // Skip the logical ports
            if (port.getIp() == null)
                continue;
            port.setHost(host1, i);
            i++;
        }

        // Create another host with these ports:
        // Router2Bridge1's ports
        // Router2Bridge2's ports
        // Note that the two hosts share the Directory and Cache.
        IntIPv4 hostAddr2 = IntIPv4.fromString("192.168.0.20");
        host2 = new Host(network.getBaseDirectory(), network.getBasePath(),
                hostAddr2, cache);
        // Using a different port-range is not necessary but avoids confusion.
        i = 200;
        ports = new ArrayList<BridgePort>();
        ports.addAll(router2bridge1.getPorts());
        ports.addAll(router2bridge2.getPorts());
        for (BridgePort port : ports) {
            // Skip the logical ports
            if (port.getIp() == null)
                continue;
            port.setHost(host2, i);
            i++;
        }

        // Host1 needs a GRE port to host2
        String grePortName = host1.getController().makeGREPortName(hostAddr2);
        host1.getController().onPortStatus(
                host1.makeGrePort((short)1002, grePortName, hostAddr2),
                OFPortStatus.OFPortReason.OFPPR_ADD);

        // Host2 needs a GRE port to host1
        grePortName = host1.getController().makeGREPortName(hostAddr1);
        host2.getController().onPortStatus(
                host2.makeGrePort((short)1001, grePortName, hostAddr1),
                OFPortStatus.OFPortReason.OFPPR_ADD);

        for (Host h : new Host[] {host1, host2} ) {
            h.getStub().deletedFlows.clear();
            h.getStub().addedFlows.clear();
        }
    }

    @Test
    public void testBridge() throws StateAccessException {
        // Send a packet from r1b1's port0 to port1.
        // The bridge hasn't yet learned port1's MAC - it should be flooded.
        router1bridge1.getPort(0).sendUDP(router1bridge1.getPort(1), true);
        // Host1 should now have a single cookie for this flooded flow.
        Set<UUID> idSet = new ElementSetBuilder()
                .flood(router1bridge1, router1bridge1.getPort(0))
                .getElementIdSet();
        long floodCookie1 = getCookieFromHost(host1, idSet, 1);
        assertThat("The host did not send any invalidations.",
                host1.getStub().deletedFlows.isEmpty());

        // Now send a packet from port1 to port2. Also flooded.
        router1bridge1.getPort(1).sendUDP(router1bridge1.getPort(2), true);
        // Learning port1's MAC should have invalidated flooded flows.
        checkCookieInvalidated(host1, floodCookie1);
        // Since port2's MAC hasn't been learned, this flow is flooded and
        // traverses the same set of elements as the previous flow. So there
        // should still be only one cookie on this host.
        assertThat("There should be 1 cookie on the host.",
                host1.getCookieManager().getNumCookies(), equalTo((long)1));

        // Now send a packet from port2 to port1.
        router1bridge1.getPort(2).sendUDP(router1bridge1.getPort(1), false);
        // Learning port2's MAC should have invalidated flooded flows.
        checkCookieInvalidated(host1, floodCookie1);
        // There should now be a cookie for the ID set {bridge, port1, port2}
        idSet = new ElementSetBuilder()
                .traverse(router1bridge1, 1, 2).getElementIdSet();
        long unicastCookie1 = getCookieFromHost(host1, idSet, 2);

        // Now send a packet from port1to port0.
        router1bridge1.getPort(1).sendUDP(router1bridge1.getPort(0), false);
        assertThat("The host did not send any invalidations.",
                host1.getStub().deletedFlows.isEmpty());
        // There should now be a cookie for the ID set {bridge, port0, port1}
        idSet = new ElementSetBuilder()
                .traverse(router1bridge1, 0, 1).getElementIdSet();
        long unicastCookie2 = getCookieFromHost(host1, idSet, 3);

        // Now port0 changes location.
        host1.getController().onPortStatus(
                router1bridge1.getPort(0).getOVSPort(),
                OFPortStatus.OFPortReason.OFPPR_DELETE);
        checkCookiesInvalidated(host1,
                new long[] {floodCookie1, unicastCookie2});

        // Now port0's MAC moves to port3.
        // Now send a packet from port3 (but 0's MAC) to port1.
        router1bridge1.getPort(3).sendUDP(
                router1bridge1.getPort(0).getMAC(),
                router1bridge1.getPort(1), false);
        // There should now be a cookie for the ID set {bridge, port3, port1}
        idSet = new ElementSetBuilder()
                .traverse(router1bridge1, 3, 1).getElementIdSet();
        long unicastCookie3 = getCookieFromHost(host1, idSet, 4);
        // Port0's MAC has moved to port3. All flows to port0 should be deleted.
        checkCookiesInvalidated(host1,
                new long[] {floodCookie1, unicastCookie2});

        // Now port2's configuration changes.
        router1bridge1.getPort(2).addFilters();
        checkCookiesInvalidated(
                host1, new long[] {floodCookie1, unicastCookie1});

        // Now the bridge's configuration changes.
        router1bridge1.changeInboundFilter();
        checkCookiesInvalidated(host1,
                new long[] {floodCookie1, unicastCookie1,
                        unicastCookie2, unicastCookie3});
    }

    @Test
    public void testRouter()
            throws StateAccessException, RuleIndexOutOfBoundsException {
        // ARP the router port to give bridges a chance to learn MACs.
        // ARPs to router ports are consumed and don't create cookies.
        List<BridgePort> ports = new ArrayList<BridgePort>();
        ports.addAll(router1bridge1.getPorts());
        ports.addAll(router1bridge2.getPorts());
        ports.addAll(router2bridge1.getPorts());
        ports.addAll(router2bridge2.getPorts());
        for (BridgePort port : ports) {
            // Don't send the ARP from the logical port.
            if (port.getIp() != null)
                port.sendRouterArpReply();
        }

        // Send a packet from r1b1 port0 to port1
        router1bridge1.getPort(0).sendUDP(router1bridge1.getPort(1), false);
        Set<UUID> idSet = new ElementSetBuilder()
                .traverse(router1bridge1, 0, 1)
                .getElementIdSet();
        long numCookies1 = 1;
        long r1b1p0Tor1b1p1 = getCookieFromHost(host1, idSet, numCookies1);

        router1bridge1.getPort(1).sendUDP(router1bridge1.getPort(0), false);
        // This packet traverses the same network elements - no new cookie
        assertThat("There should be the same number of cookies on the host.",
                host1.getCookieManager().getNumCookies(), equalTo(numCookies1));

        // Send a packet from r2b2 port0 to port1
        router2bridge2.getPort(0).sendUDP(router2bridge2.getPort(1), false);
        idSet = new ElementSetBuilder()
                .traverse(router2bridge2, 0, 1)
                .getElementIdSet();
        long numCookies2 = 1;
        long r2b2p0Tor2b2p1 = getCookieFromHost(host2, idSet, numCookies2);

        // Send a packet from r1b1 port0 to r1b2 port0
        router1bridge1.getPort(0).sendUDP(router1bridge2.getPort(0), false);
        idSet = new ElementSetBuilder()
                .traverse(router1bridge1, 0, 100)
                .traverse(router1, "portToBridge1", "portToBridge2")
                .traverse(router1bridge2, 100, 0)
                .getElementIdSet();
        numCookies1++;
        long r1b1p0tor1b2p0 = getCookieFromHost(host1, idSet, numCookies1);

        router1bridge2.getPort(0).sendUDP(router1bridge1.getPort(0), false);
        // This packet traverses the same network elements - no new cookie
        assertThat("There should be the same number of cookies on the host.",
                host1.getCookieManager().getNumCookies(), equalTo(numCookies1));

        // Send a packet from r2b1 port0 to r2b2 port0
        router2bridge1.getPort(0).sendUDP(router2bridge2.getPort(0), false);
        idSet = new ElementSetBuilder()
                .traverse(router2bridge1, 0, 100)
                .traverse(router2, "portToBridge1", "portToBridge2")
                .traverse(router2bridge2, 100, 0)
                .getElementIdSet();
        numCookies2++;
        long r2b1p0tor2b2p0 = getCookieFromHost(host2, idSet, numCookies2);

        router2bridge2.getPort(0).sendUDP(router2bridge1.getPort(0), false);
        // This packet traverses the same network elements - no new cookie
        assertThat("There should be the same number of cookies on the host.",
                host2.getCookieManager().getNumCookies(), equalTo(numCookies2));

        // Now send a packet from r1b2 port0 to r2b2port0. This packet traverses
        // the provider router. Note that we need to use a public dst address.
        router1bridge1.getPort(0).sendUDP("112.0.0.22");
        idSet = new ElementSetBuilder()
                .traverse(router1bridge1, 0, 100)
                .traverse(router1, "portToBridge1", "uplink")
                .traverse(provRouter, "portToTenant1", "portToTenant2")
                .traverse(router2, "uplink", "portToBridge2")
                .traverse(router2bridge2, 100, 0)
                .getElementIdSet();
        numCookies1++;
        long r1b1p0tor2b2p0 = getCookieFromHost(host1, idSet, numCookies1);

        // Now send a packet from r2b1 port0 to r1b2port0. This packet traverses
        // the provider router. Note that we need to use a public dst address.
        router2bridge1.getPort(0).sendUDP("112.0.0.2");
        idSet = new ElementSetBuilder()
                .traverse(router2bridge1, 0, 100)
                .traverse(router2, "portToBridge1", "uplink")
                .traverse(provRouter, "portToTenant2", "portToTenant1")
                .traverse(router1, "uplink", "portToBridge2")
                .traverse(router1bridge2, 100, 0)
                .getElementIdSet();
        numCookies2++;
        long r2b1p0tor1b2p0 = getCookieFromHost(host2, idSet, numCookies2);

        // Let's recap the cookies that were created:
        // On host1: r1b1p0Tor1b1p1, r1b1p0tor1b2p0, r1b1p0tor2b2p0
        // On host2: r2b2p0Tor2b2p1, r2b1p0tor2b2p0, r2b1p0tor1b2p0

        // Check that we're starting without invalidations.
        assertThat("The host did not send any invalidations.",
                host1.getStub().deletedFlows.isEmpty());
        assertThat("The host did not send any invalidations.",
                host2.getStub().deletedFlows.isEmpty());

        // If r1b2's configuration changes, cookies with "r1b2" are invalidated.
        router1bridge2.changeInboundFilter();
        checkCookieInvalidated(host1, r1b1p0tor1b2p0);
        checkCookieInvalidated(host2, r2b1p0tor1b2p0);

        // If r1b1's port1's configuration changes, cookies with "r1b1p1" are
        // invalidated.
        router1bridge1.getPort(1).addFilters();
        checkCookieInvalidated(host1, r1b1p0Tor1b1p1);
        assertThat("The host did not send any invalidations.",
                host2.getStub().deletedFlows.isEmpty());

        // If r1b1's uplink's configuration changes, all paths with "r1b1" and a
        // different bridge will be invalidated.
        router1bridge1.getPort("uplink").addFilters();
        checkCookiesInvalidated(host1, new long[] {
                r1b1p0tor1b2p0, r1b1p0tor2b2p0});
        assertThat("The host did not send any invalidations.",
                host2.getStub().deletedFlows.isEmpty());

        // If r1's inbound chain rules change, all paths with both r1's bridges,
        // or r1 and r2, will be invalidated.
        router1.addDummyInboundRule();
        checkCookiesInvalidated(
                host1, new long[] {r1b1p0tor1b2p0, r1b1p0tor2b2p0});
        checkCookieInvalidated(host2, r2b1p0tor1b2p0);

        // If r1's outbound chain rules change, all paths with both r1's
        // bridges, or r1 and r2, will be invalidated.
        router1.addDummyOutboundRule();
        checkCookiesInvalidated(
                host1, new long[] {r1b1p0tor1b2p0, r1b1p0tor2b2p0});
        checkCookieInvalidated(host2, r2b1p0tor1b2p0);

        // If r1's configuration changes, all paths with both r1's bridges,
        // or r1 and r2, will be invalidated.
        router1.changeInboundFilter();
        checkCookiesInvalidated(
                host1, new long[] {r1b1p0tor1b2p0, r1b1p0tor2b2p0});
        checkCookieInvalidated(host2, r2b1p0tor1b2p0);

        // Note that if we add a rule on the new inbound filter, no flows
        // will be invalidated because no flow was processed by the new chain.
        router1.addDummyInboundRule();
        assertThat("The host did not send any invalidations.",
                host1.getStub().deletedFlows.isEmpty());
        assertThat("The host did not send any invalidations.",
                host2.getStub().deletedFlows.isEmpty());

        // The provider router's configuration changes, any flow that traversed
        // both routers is invalidated.
        provRouter.changeInboundFilter();
        checkCookieInvalidated(host1, r1b1p0tor2b2p0);
        checkCookieInvalidated(host2, r2b1p0tor1b2p0);

        // The provider router's uplink's configuration changes:
        // no flows invalidated since none traversed that port.
        provRouter.getPort("uplink").addInboundFilter();
        assertThat("The host did not send any invalidations.",
                host1.getStub().deletedFlows.isEmpty());
        assertThat("The host did not send any invalidations.",
                host2.getStub().deletedFlows.isEmpty());
    }

    @Test
    public void testDistributedBridge()
            throws StateAccessException, RuleIndexOutOfBoundsException {
        // Make a new bridge with 6 ports.
        Bridge router1bridge3 = network.makeBridge();
        router1.linkBridge(
                "portToBridge3", "10.0.3.1", router1bridge3, "uplink", 100);
        router1.getPort("portToBridge3").addRoute("10.0.3.0", 24, null);
        // Add some floating IPs for VMs on this bridge.
        router1.addFloatingIP("10.0.3.2", "uplink", "112.0.0.32");
        router1.addFloatingIP("10.0.3.3", "uplink", "112.0.0.33");
        router1.addFloatingIP("10.0.3.4", "uplink", "112.0.0.34");
        router1.addFloatingIP("10.0.3.5", "uplink", "112.0.0.35");
        router1.addFloatingIP("10.0.3.6", "uplink", "112.0.0.36");
        router1bridge3.addPorts(0, 6);

        // Distribute the 6 ports among 3 hosts. Create the 3rd host now.
        IntIPv4 hostAddr3 = IntIPv4.fromString("192.168.0.30");
        Host host3 = new Host(network.getBaseDirectory(), network.getBasePath(),
                hostAddr3, cache);
        Host[] hosts = new Host[] {host1, host2, host3};
        // Use a different port-range to avoid confusion.
        for (int i = 0; i < 6; i++) {
            BridgePort port = router1bridge3.getPort(i);
            Host h = hosts[i % hosts.length];
            port.setHost(h, (short)(300 + i));
            // While we're at it, also create filters on each of these ports.
            port.addFilters();
        }

        // Host1 needs a GrePort to Host3
        String grePortName = host1.getController().makeGREPortName(hostAddr3);
        host1.getController().onPortStatus(
                host1.makeGrePort((short)1003, grePortName, hostAddr3),
                OFPortStatus.OFPortReason.OFPPR_ADD);

        // Host2 needs a GrePort to Host3
        host2.getController().onPortStatus(
                host2.makeGrePort((short)1003, grePortName, hostAddr3),
                OFPortStatus.OFPortReason.OFPPR_ADD);

        // Host3 needs GrePorts to Host1 and Host2
        grePortName = host1.getController().makeGREPortName(
                host1.getController().publicIp);
        host3.getController().onPortStatus(
                host3.makeGrePort((short)1001, grePortName,
                        host1.getController().publicIp),
                OFPortStatus.OFPortReason.OFPPR_ADD);
        grePortName = host2.getController().makeGREPortName(
                host2.getController().publicIp);
        host3.getController().onPortStatus(
                host3.makeGrePort((short)1002, grePortName,
                        host2.getController().publicIp),
                OFPortStatus.OFPortReason.OFPPR_ADD);

        // Clear the invalidations and added default flows.
        for (Host h : new Host[] {host1, host2, host3} ) {
            h.getStub().deletedFlows.clear();
            h.getStub().addedFlows.clear();
        }

        byte[] data = router1bridge3.getPort(0).sendUDP(
                router1bridge3.getPort(1), false);
        // Only host1 should have a flow so far. Since the port1's MAC hasn't
        // been learned, the flow should be flooded. And since host1 only has
        // ports 0 and 3, only those ports' filters should be included.
        Set<UUID> idSet = new ElementSetBuilder()
                .flood(router1bridge3, router1bridge3.getPort(0))
                .getElementIdSet();
        long host1flood1 = getCookieFromHost(host1, idSet, 1);
        assertThat("There should be zero cookies on host2.",
                host2.getCookieManager().getNumCookies(), equalTo((long)0));
        assertThat("There should be zero cookies on host3.",
                host2.getCookieManager().getNumCookies(), equalTo((long)0));
        for (Host h : new Host[] {host1, host2, host3} )
            assertThat("The host did not send any invalidations.",
                    h.getStub().deletedFlows.isEmpty());

        // Now simulate the flooded packet arriving at host2 and host3
        host2.getController().onPacketIn(12345, data.length, (short)1001,
                data, router1bridge3.getConfig().greKey);
        idSet = new ElementSetBuilder()
                .egressFlood(router1bridge3, host2)
                .getElementIdSet();
        long host2flood1 = getCookieFromHost(host2, idSet, 1);
        host3.getController().onPacketIn(54321, data.length, (short)1001,
                data, router1bridge3.getConfig().greKey);
        idSet = new ElementSetBuilder()
                .egressFlood(router1bridge3, host3)
                .getElementIdSet();
        long host3flood1 = getCookieFromHost(host3, idSet, 1);

        // If the bridge's configuration changes, only host1's flow will be
        // be deleted because the egress hosts only care about the port
        // and the port's outboundFilter. This makes sense because if anything
        // else in the original flow's path changes, the ingress controller
        // would stop sending the flow.
        router1bridge3.changeInboundFilter();
        checkCookieInvalidated(host1, host1flood1);
        for (Host h : new Host[] {host2, host3} )
            assertThat("The host did not send any invalidations.",
                    h.getStub().deletedFlows.isEmpty());

        // If port0's configuration changes, only host1's flow is invalidated.
        router1bridge3.getPort(0).addFilters();
        checkCookieInvalidated(host1, host1flood1);
        for (Host h : new Host[] {host2, host3} )
            assertThat("The host did not send any invalidations.",
                    h.getStub().deletedFlows.isEmpty());

        // If port1's inbound chain changes, nothing is invalidated.
        router1bridge3.getPort(1).addDummyInboundRule();
        for (Host h : new Host[] {host1, host2, host3} )
            assertThat("The host did not send any invalidations.",
                    h.getStub().deletedFlows.isEmpty());

        // If port1's outbound chain changes, only host2's flow is invalidated.
        router1bridge3.getPort(1).addDummyOutboundRule();
        checkCookieInvalidated(host2, host2flood1);
        for (Host h : new Host[] {host1, host3} )
            assertThat("The host did not send any invalidations.",
                    h.getStub().deletedFlows.isEmpty());

        // If port2's config changes, only host3's flow is invalidated.
        router1bridge3.getPort(2).addFilters();
        checkCookieInvalidated(host3, host1flood1);
        for (Host h : new Host[] {host1, host2} )
            assertThat("The host did not send any invalidations.",
                    h.getStub().deletedFlows.isEmpty());

        // TODO(pino): test more MAC learning with the distributed bridge.
        // TODO(pino): test mac changing port.
        // TODO(pino): test port changing host (physical location)
    }

    private void checkCookieInvalidated(Host h, long cookie) {
        checkCookiesInvalidated(h, new long[]{cookie});
    }

    private void checkCookiesInvalidated(Host h, long[] cookies) {
        Set<Long> invalidatedCookies = new HashSet<Long>();
        for (MockControllerStub.Flow flow : h.getStub().deletedFlows) {
            if (flow.match.getWildcards() == OFMatch.OFPFW_ALL
                    && flow.cookie > 0)
                invalidatedCookies.add(flow.cookie);
        }
        assertThat("There should be " + cookies.length + " cookies invalidated",
                invalidatedCookies.size(), equalTo(cookies.length));
        for (Long cookie : cookies) {
            assertThat("The invalidations should include each expected cookie.",
                    invalidatedCookies, hasItem(cookie));
        }
        h.getStub().deletedFlows.clear();
    }

    private long getCookieFromHost(Host h, Set<UUID> idSet,
                                   long expectedNumCookies) {
        assertThat("There should be " + expectedNumCookies +
                " cookies on the host.",
                h.getCookieManager().getNumCookies(),
                equalTo(expectedNumCookies));
        assertThat("There should be a cookie for the set " + idSet.toString(),
                h.getCookieManager().hasCookieForIdSet(idSet));
        return h.getCookieManager().getCookieForIdSet(idSet);
    }

    private static class ElementSetBuilder {
        Set<UUID> idSet = new HashSet<UUID>();

        ElementSetBuilder traverse(
                Router router, String inPortName, String outPortName) {
            RouterPort inPort = router.getPort(inPortName);
            RouterPort outPort = router.getPort(outPortName);
            idSet.add(router.getId());
            if (null != router.getConfig().inboundFilter)
                idSet.add(router.getConfig().inboundFilter);
            if (null != router.getConfig().outboundFilter)
                idSet.add(router.getConfig().outboundFilter);
            idSet.add(inPort.getId());
            if(null != inPort.getConfig().inboundFilter)
                idSet.add(inPort.getConfig().inboundFilter);
            idSet.add(outPort.getId());
            if(null != outPort.getConfig().outboundFilter)
                idSet.add(outPort.getConfig().outboundFilter);
            return this;
        }

        ElementSetBuilder traverse(
                Bridge bridge, int inPortIndex, int outPortIndex) {
            BridgePort inPort = bridge.getPort(inPortIndex);
            BridgePort outPort = bridge.getPort(outPortIndex);
            idSet.add(bridge.getId());
            if (null != bridge.getConfig().inboundFilter)
                idSet.add(bridge.getConfig().inboundFilter);
            if (null != bridge.getConfig().outboundFilter)
                idSet.add(bridge.getConfig().outboundFilter);
            idSet.add(inPort.getId());
            if(null != inPort.getConfig().inboundFilter)
                idSet.add(inPort.getConfig().inboundFilter);
            idSet.add(outPort.getId());
            if(null != outPort.getConfig().outboundFilter)
                idSet.add(outPort.getConfig().outboundFilter);
            return this;
        }

        ElementSetBuilder flood(Bridge bridge, BridgePort inPort) {
            // Traverse the bridge itself and its 'flood' element.
            idSet.add(bridge.getId());
            idSet.add(bridge.getFloodId());
            // Traverse the bridge's filters.
            if (null != bridge.getConfig().inboundFilter)
                idSet.add(bridge.getConfig().inboundFilter);
            if (null != bridge.getConfig().outboundFilter)
                idSet.add(bridge.getConfig().outboundFilter);
            // Traverse the ingress port's inbound filter.
            if(null != inPort.getConfig().inboundFilter)
                idSet.add(inPort.getConfig().inboundFilter);
            // Traverse all the local ports. Their outbound filters are not
            // traversed if they are local.
            // Note: Flooded packets are not emitted from the ingress port;
            // however, the ingress is included in the set because it was
            // traversed by the packet.
            egressFlood(bridge, inPort.getHost());
            idSet.remove(inPort.getConfig().outboundFilter);
            return this;
        }

        ElementSetBuilder egressFlood(Bridge bridge, Host h) {
            for (BridgePort port : bridge.getPorts()) {
                // Flooded packets are not emitted from logical ports.
                if (null != port.getIp() && port.getHost() == h) {
                    idSet.add(port.getId());
                    idSet.add(port.getConfig().outboundFilter);
                }
            }
            return this;
        }

        Set<UUID> getElementIdSet() {
            return idSet;
        }
    }
}
