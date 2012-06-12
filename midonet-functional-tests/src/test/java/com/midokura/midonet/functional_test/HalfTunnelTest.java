/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.nio.ByteBuffer;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.GRE;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.packets.MalformedPacketException;
import com.midokura.midonet.functional_test.openflow.PrimaryController;
import com.midokura.midonet.functional_test.topology.BridgePort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.TapWrapper;
import com.midokura.midonet.functional_test.utils.MidolmanLauncher;
import com.midokura.util.process.ProcessHelper;


import static com.midokura.midonet.functional_test.EndPoint.exchangeArpWithGw;
import static com.midokura.midonet.functional_test.FunctionalTestsHelper.*;
import static com.midokura.midonet.functional_test.utils.MidolmanLauncher.ConfigType.Without_Bgp;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HalfTunnelTest  extends RouterBridgeBaseTest {
    private final static Logger log =
            LoggerFactory.getLogger(HalfTunnelTest.class);

    PrimaryController controller;
    OvsBridge greBridge;
    MidolmanLauncher midolman2;
    OvsBridge ovsBridge2;
    EndPoint mm2endpoint;
    BridgePort mm2bport;
    String internalPortName = "tunTestInt";
    IntIPv4 mm1Addr = IntIPv4.fromString("172.29.10.4");
    IntIPv4 mm2Addr = IntIPv4.fromString("172.29.10.5");
    MAC fakeMac = MAC.random();

    @Before
    public void setup() throws Exception {
        // Create a second MM and OVS bridge that will have a single
        // materialized port. The first and second MM will open GRE tunnels
        // to each other when they discover each other in the PortLocMap.
        midolman2 = MidolmanLauncher.start(Without_Bgp, "RouterBridgeBaseTest");
        if (ovsdb.hasBridge("smoke-br2"))
            ovsdb.delBridge("smoke-br2");
        ovsBridge2 = new OvsBridge(ovsdb, "smoke-br2", "tcp:127.0.0.1:6657");

        mm2endpoint = new EndPoint(
                IntIPv4.fromString("10.0.0.55"),
                MAC.fromString("02:aa:bb:cc:dd:55"),
                routerDownlink.getIpAddr(),
                routerDownlink.getMacAddr(),
                new TapWrapper("tuntestTap"));
        mm2bport = bridge1.addPort().build();
        ovsBridge2.addSystemPort(mm2bport.getId(), mm2endpoint.tap.getName());

        // Create an OVS bridge and internal port that we can use to capture
        // the second MM's GRE packets.
        if (ovsdb.hasBridge("halftun-br"))
            ovsdb.delBridge("halftun-br");
        // Create a bridge with an internal port with address equal to MM2.
        // Create a PrimaryController that allows us to snoop the packets.
        controller =
                new PrimaryController(8888, PrimaryController.Protocol.NXM);
        greBridge = new OvsBridge(ovsdb, "halftun-br", "tcp:127.0.0.1:8888");
        // Create one internal port on the bridge. Since it has the same
        // address as the second MM's public address in the .conf, GRE packets
        // from that MM will be emitted from this interface and arrive on the
        // 'greBridge' where we can examine them.
        greBridge.addInternalPort(
                UUID.randomUUID(), internalPortName, mm2Addr, 24);
        // Need to seed the ARP cache (neighbor table) for the first MM's IP
        // address (172.29.10.4) so that the second MM's GRE packets don't
        // trigger an ARP.
        // set link up to that port
        runCommandAndWait(
                String.format("sudo -n ip neighbor add %s  " +
                        "lladdr %s dev %s nud permanent",
                        mm1Addr.toUnicastString(), fakeMac.toString(),
                        internalPortName));

        Thread.sleep(5000);
        assertTrue(controller.waitForBridge(greBridge.getName()));
        assertTrue(controller.waitForPort(internalPortName));
    }

    @After
    public void tearDown() throws InterruptedException {
        if (controller != null &&
                controller.getStub() != null)
            controller.getStub().close();
        removeBridge(greBridge);

        stopMidolman(midolman2);
        removeTapWrapper(mm2endpoint.tap);
        removeBridge(ovsBridge2);
        mm2bport.delete();
        sleepBecause("The other Midolman should notice this one exited.", 2);
    }

    @Test
    public void test() throws MalformedPacketException, InterruptedException {
        // Let the virtual bridge learn a MAC from one port on each OVS bridge.
        exchangeArpWithGw(mm2endpoint);
        exchangeArpWithGw(vmEndpoints.get(0));

        // Now send a packet from MM2's port to MM1's port.
        byte[] sent = PacketHelper.makeIcmpEchoRequest(
                mm2endpoint.mac, mm2endpoint.ip,
                vmEndpoints.get(0).mac, vmEndpoints.get(0).ip);
        assertThat("The packet should have been sent from the source tap.",
                mm2endpoint.tap.send(sent));

        // TODO(pino): properly find the GRE key of the bridge's 1st vport.
        // The GRE key should be 3 since bPort0 was created after the router's
        // materialized port and the bridge itself.
        verifyGreMM2to1(sent, 3);

        // Resend the packet.
        assertThat("The packet should have been sent from the source tap.",
                mm2endpoint.tap.send(sent));
        verifyGreMM2to1(sent, 3);

        // Send a packet from MM2's port to an unlearned MAC, this should
        // result in a tunneled packet with a tunnel ID corresponding to the
        // virtual bridge.
        sent = PacketHelper.makeIcmpEchoRequest(
                mm2endpoint.mac, mm2endpoint.ip,
                MAC.random(), vmEndpoints.get(0).ip);
        assertThat("The packet should have been sent from the source tap.",
                mm2endpoint.tap.send(sent));

        // TODO(pino): properly find the GRE key for the vBridge's FLOOD.
        // The GRE key should be 2 since the Bridge is created after the
        // router's uplink.
        verifyGreMM2to1(sent, 2);

        // Resend the packet.
        assertThat("The packet should have been sent from the source tap.",
                mm2endpoint.tap.send(sent));
        verifyGreMM2to1(sent, 2);
    }

    protected void verifyGreMM2to1(byte[] sent, int tunnelId)
            throws InterruptedException, MalformedPacketException {
        // The packet should be forwarded unmodified over the virtual bridge.
        // It should therefore be tunneled to MM1's public address, and
        // so it arrives at our 'snooping' controller.
        PrimaryController.PacketIn pktIn;
        Ethernet ethPkt;
        while (true) {
            pktIn = controller.getNextPacket();
            assertNotNull(pktIn);
            assertThat("The internal port received a packet.", pktIn.inPort,
                    equalTo(controller.getPortNum(internalPortName)));
            ethPkt = new Ethernet();
            ethPkt.deserialize(ByteBuffer.wrap(pktIn.packet));
            log.debug("Received packet {}", ethPkt);
            // Break out of this loop when we find a packet addressed to the
            // MAC address of MM1 - that's the tunneled packet.
            if (ethPkt.getDestinationMACAddress().equals(fakeMac)
                && ethPkt.getEtherType() == IPv4.ETHERTYPE)
                break;
        }
        assertThat("The controller got the whole tunneled packet.",
                pktIn.packet.length, equalTo(pktIn.totalLen));
        assertThat("IPv4 packet", ethPkt.getEtherType(), equalTo(IPv4.ETHERTYPE));
        IPv4 ipPkt = IPv4.class.cast(ethPkt.getPayload());
        assertThat("Source IP", ipPkt.getSourceAddress(),
                equalTo(mm2Addr.getAddress()));
        assertThat("Destination IP", ipPkt.getDestinationAddress(),
                equalTo(mm1Addr.getAddress()));
        assertThat("GRE packet", ipPkt.getProtocol(),
                equalTo(GRE.PROTOCOL_NUMBER));
        GRE grePkt = GRE.class.cast(ipPkt.getPayload());
        assertThat("The GRE protocol is 0x6558, transparent ethernet bridging.",
                grePkt.getProtocol(), equalTo(GRE.PTYPE_BRIDGING));
        assertThat("The GRE key is the destination vport's GRE key.",
                grePkt.getKey(), equalTo(tunnelId));
        byte[] grePayload = grePkt.getPayload().serialize();
        // The gre payload should equal the Eth pkt sent from mm2's endpoint.
        assertThat("The payload equals the pkt we sent from mm2's port.",
                grePayload, equalTo(sent));
    }

    protected void runCommandAndWait(String command) throws Exception {
        ProcessHelper
                .newProcess(command)
                .logOutput(log, command)
                .runAndWait();
    }
}
