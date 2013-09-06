/*
 * Copyright 2013 Midokura Europe SARL
 */

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import akka.util.Duration;
import org.junit.Test;
import org.midonet.client.dto.DtoInteriorBridgePort;
import org.midonet.client.dto.DtoRoute;
import org.midonet.client.dto.DtoRule;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.BridgePort;
import org.midonet.client.resource.Router;
import org.midonet.client.resource.RouterPort;
import org.midonet.client.resource.RuleChain;
import org.midonet.functional_test.PacketHelper;
import org.midonet.functional_test.TestBase;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.midolman.topology.LocalPortActive;
import org.midonet.packets.Ethernet;
import org.midonet.packets.ICMP;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;
import org.midonet.packets.MalformedPacketException;
import org.midonet.packets.TCP;
import org.midonet.packets.UDP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.midonet.functional_test.FunctionalTestsHelper.assertNoMorePacketsOnTap;
import static org.midonet.functional_test.FunctionalTestsHelper.arpAndCheckReplyDrainBroadcasts;
import static org.midonet.functional_test.FunctionalTestsHelper.bindTapsToBridgePorts;
import static org.midonet.functional_test.FunctionalTestsHelper.buildBridgePorts;
import static org.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;
import static org.midonet.functional_test.PacketHelper.makeArpReply;
import static org.midonet.functional_test.PacketHelper.makeIcmpEchoReply;
import static org.midonet.functional_test.PacketHelper.makeIcmpEchoRequest;

public class NatTest extends TestBase {

    public final static Logger log = LoggerFactory.getLogger(NatTest.class);

    MAC rtrMacPriv;
    MAC rtrMacExt = MAC.fromString("aa:bb:cc:dd:33:11");
    MAC vm1Mac = MAC.fromString("aa:bb:cc:dd:11:22");
    MAC vm2Mac = MAC.fromString("aa:bb:cc:dd:22:22");
    MAC extMac = MAC.fromString("99:88:77:66:55:44");

    IPv4Subnet extNw = new IPv4Subnet("192.168.1.0", 24);
    IPv4Subnet privNw = new IPv4Subnet("10.0.1.0", 24);

    IPv4Subnet rtrIpPriv = new IPv4Subnet("10.0.1.100", 24);
    IPv4Subnet rtrIpExt = new IPv4Subnet("192.168.1.100", 24);
    IPv4Subnet vm1Ip = new IPv4Subnet("10.0.1.1", 24);
    IPv4Subnet vm2Ip = new IPv4Subnet("10.0.1.2", 24);
    IPv4Subnet extIp = new IPv4Subnet("192.168.1.99", 24);

    Router rtr;
    RouterPort rtrPortInt;
    RouterPort rtrPortExt;

    Bridge br;

    TapWrapper tapExt;
    TapWrapper[] taps;

    RuleChain pre;
    RuleChain post;

    @Override
    public void teardown() {
        for (TapWrapper t : taps)
            removeTapWrapper(t);
    }

    @Override
    public void setup() {

        rtr = apiClient.addRouter().tenantId("tenant-1").name("rtr").create();

        // PORTS
        rtrPortInt = rtr
            .addInteriorRouterPort()
            .portAddress(rtrIpPriv.toUnicastString())
            .networkAddress(rtrIpPriv.toNetworkAddress().toString())
            .networkLength(rtrIpPriv.getPrefixLen())
            .create();
        rtrPortExt = rtr
            .addExteriorRouterPort()
            .portAddress(rtrIpExt.toUnicastString())
            .networkAddress(rtrIpExt.toNetworkAddress().toString())
            .networkLength(rtrIpExt.getPrefixLen())
            .create();
        log.info("The Router's interior port is {}", rtrPortInt);
        log.info("The Router's exterior port is {}", rtrPortExt);

        // ROUTES
        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
           .dstNetworkAddr(privNw.toNetworkAddress().toString())
           .dstNetworkLength(privNw.getPrefixLen())
           .nextHopPort(rtrPortInt.getId())
           .type(DtoRoute.Normal).weight(10)
           .create();

        rtr.addRoute().srcNetworkAddr("0.0.0.0").srcNetworkLength(0)
           .dstNetworkAddr(extNw.toNetworkAddress().toString())
           .dstNetworkLength(extNw.getPrefixLen())
           .nextHopPort(rtrPortExt.getId())
           .type(DtoRoute.Normal).weight(10)
           .create();

        // MACs
        rtrMacPriv = MAC.fromString(rtrPortInt.getPortMac());
        rtrMacExt = MAC.fromString(rtrPortExt.getPortMac());

        // HOOK BRIDGE ON PRIVATE PORT
        br = apiClient.addBridge()
                      .tenantId("tenant-1")
                      .name("br")
                      .create();

        // Add some exterior ports and bind to taps
        taps = new TapWrapper[3];
        BridgePort[] brPorts = buildBridgePorts(br, true, 2);
        TapWrapper[] brTaps = bindTapsToBridgePorts(thisHost, brPorts,
                                                    "natTestTapInt", probe);
        taps[0] = brTaps[0];
        taps[1] = brTaps[1];
        taps[2] = tapExt = new TapWrapper("ext");
        thisHost.addHostInterfacePort()
            .interfaceName(tapExt.getName())
            .portId(rtrPortExt.getId()).create();

        log.info("Waiting router external tap to become active");
        LocalPortActive activeMsg = probe.expectMsgClass(
            Duration.create(10, TimeUnit.SECONDS), LocalPortActive.class);
        assertTrue("The port should be active.", activeMsg.active());
        assertEquals("The port id should match.", activeMsg.portID(),
                                                  rtrPortExt.getId());
        // Bind the bridge to the router.
        BridgePort<DtoInteriorBridgePort> logBrPort =
            br.addInteriorPort().create();
        rtrPortInt.link(logBrPort.getId());

        // CHAINS
        pre = apiClient.addChain().name("pre-routing")
                                  .tenantId("tenant-1").create();
        post = apiClient.addChain().name("post-routing")
                                   .tenantId("tenant-1").create();
        rtr.inboundFilterId(pre.getId())
           .outboundFilterId(post.getId())
           .update();
    }

    /**
     * SNAT: dst = priv network -> ext network
     */
    private void setSnat() {
        pre.addRule().type(DtoRule.RevSNAT).flowAction(DtoRule.Continue).create();
        post.addRule().type(DtoRule.SNAT).flowAction(DtoRule.Accept)
            .nwSrcAddress(privNw.toUnicastString())
            .nwSrcLength(privNw.getPrefixLen())
            .nwDstAddress(extNw.toUnicastString())
            .nwDstLength(extNw.getPrefixLen())
            .outPorts(new UUID[]{rtrPortExt.getId()})
            .natTargets(new DtoRule.DtoNatTarget[]{
                new DtoRule.DtoNatTarget(rtrIpExt.toUnicastString(),
                                         rtrIpExt.toUnicastString(),
                                         1000, 1010)
            }).create();
    }

    /**
     * DNAT: from !priv nw -> rtr ext ip, DNAT to vm1
     */
    private void setDnat() {
        post.addRule().type(DtoRule.RevDNAT).flowAction(DtoRule.Continue).create();
        pre.addRule().type(DtoRule.DNAT).flowAction(DtoRule.Accept)
            .nwSrcAddress(privNw.toUnicastString())
            .nwSrcLength(privNw.getPrefixLen())
            .invNwSrc(true)
            .nwDstAddress(rtrIpExt.toUnicastString())
            .inPorts(new UUID[]{rtrPortExt.getId()})
            .natTargets(new DtoRule.DtoNatTarget[]{
                new DtoRule.DtoNatTarget(vm1Ip.toUnicastString(),
                                         vm1Ip.toUnicastString(),
                                         1000, 1000)
            }).create();
    }

    /**
     * Feeds ARP tables in the router.
     *
     * @throws Exception
     */
    private void feedRouterARPTable() throws Exception {

        // Send ARPs from each VM so that the router can learn MACs.
        arpAndCheckReplyDrainBroadcasts(taps[0], vm1Mac,
                vm1Ip.getAddress(), rtrIpPriv.getAddress(), rtrMacPriv, taps);
        arpAndCheckReplyDrainBroadcasts(taps[1], vm2Mac,
                vm2Ip.getAddress(), rtrIpPriv.getAddress(), rtrMacPriv, taps);
        // Feed router's ARP table with the extIp's MAC
        tapExt.send(makeArpReply(extMac, extIp.getAddress(),
                rtrMacExt, rtrIpExt.getAddress()));

        log.info("Waiting while the router learns the remote host's MAC");
        Thread.sleep(500);

        for (TapWrapper t : taps)
            assertNoMorePacketsOnTap(t);

    }

    /**
     * Sends an ICMP req. from (dlSrc, nwSrc) to (dlDst, nwDst) and expects it
     * to arrive at the destination with the ICMP contents intact, but having
     * the expected nwSrc and nwDst.
     *
     * @throws MalformedPacketException
     */
    private void icmpArrivesNATed(TapWrapper tapSrc, TapWrapper tapDst,
                                  MAC dlSrc, MAC dlDst,
                                  IPv4Addr nwSrc, IPv4Addr nwDst,
                                  IPv4Addr natAddr, boolean isSnat)
        throws MalformedPacketException {

        log.info("Sending ICMP request from {} to {} ", nwSrc, nwDst);
        byte[] out = makeIcmpEchoRequest(dlSrc, nwSrc, dlDst, nwDst);
        Ethernet origEth = Ethernet.deserialize(out);
        ICMP origIcmp = ICMP.class.cast(IPv4.class.cast(origEth.getPayload())
                                                  .getPayload());
        assertThat("The request is sent from the source tap", tapSrc.send(out));

        // Expect the packet in
        byte[] in = tapDst.recv();
        assertNotNull("The request arrives at the destination tap", in);

        // Check contents
        Ethernet eth = Ethernet.deserialize(in);
        IPv4 ip = IPv4.class.cast(eth.getPayload());
        ICMP icmp = ICMP.class.cast(ip.getPayload());
        log.info("This is the IPv4 received as request {}", ip);

        if (isSnat) {
            assertEquals(natAddr.addr(), ip.getSourceAddress());
            assertEquals(nwDst.addr(), ip.getDestinationAddress());
        } else {
            assertEquals(nwSrc.addr(), ip.getSourceAddress());
            assertEquals(natAddr.addr(), ip.getDestinationAddress());
        }

        assertEquals(origIcmp.getType(), icmp.getType());
        assertEquals(origIcmp.getCode(), icmp.getCode());
        assertEquals(origIcmp.getSequenceNum(), icmp.getSequenceNum());
        assertEquals(origIcmp.getIdentifier(), icmp.getIdentifier());

        log.info("Sending ICMP reply");
        out = makeIcmpEchoReply(eth.getDestinationMACAddress(),
                                new IPv4Addr(ip.getDestinationAddress()),
                                eth.getSourceMACAddress(),
                                new IPv4Addr(ip.getSourceAddress()),
                                icmp.getIdentifier(), icmp.getSequenceNum());
        assertThat("The reply is sent from the destination tap",
                   tapDst.send(out));
        origEth = Ethernet.deserialize(out);
        origIcmp = ICMP.class.cast(IPv4.class.cast(origEth.getPayload())
                                             .getPayload());

        in = tapSrc.recv();
        assertNotNull("The reply arrives at the source tap", in);

        eth = Ethernet.deserialize(in);
        ip = IPv4.class.cast(eth.getPayload());
        icmp = ICMP.class.cast(ip.getPayload());
        log.info("This is the IPv4 received as reply {}", ip);

        // Expects are reversed because this is matching on the reply
        assertEquals(nwDst.addr(), ip.getSourceAddress());
        assertEquals(nwSrc.addr(), ip.getDestinationAddress());
        assertEquals(origIcmp.getType(), icmp.getType());
        assertEquals(origIcmp.getCode(), icmp.getCode());
        assertEquals(origIcmp.getSequenceNum(), icmp.getSequenceNum());
        assertEquals(origIcmp.getIdentifier(), icmp.getIdentifier());

    }

    /**
     * Sends an ICMP UNREACHABLE HOST in reply to natdIp from tapSrc and
     * expects it in tapDst, will check that the received ICMP ERROR data field
     * contains the origIp header as well as part of the origIp payload.
     *
     * This is because when origIp passes the NAT rules it'll have its src/dst
     * translated. This packet will be put in the ICMP ERROR data field, so
     * they will also need to be un-translated when the error travels to the
     * original source.
     *
     * @param tapSrc
     * @param tapDst
     * @param dlSrc
     * @param dlDst
     * @param origIp
     * @param natdIp
     * @throws Exception
     */
    private void icmpErrorAndCheck(TapWrapper tapSrc, TapWrapper tapDst,
                                   MAC dlSrc, MAC dlDst,
                                   IPv4 origIp, IPv4 natdIp)
        throws Exception {

        // Make an ICMP error with the packet as it was received (NAT'ed)
        byte[] out = PacketHelper.makeIcmpErrorUnreachable(
            dlSrc, dlDst, ICMP.UNREACH_CODE.UNREACH_HOST, natdIp);
        assertThat("The ICMP error is sent", tapSrc.send(out));

        byte[] in = tapDst.recv();
        assertNotNull("The reply packet arrives", in);

        Ethernet eth = Ethernet.deserialize(in);
        log.info("Received eth: {}", eth);
        IPv4 ip = IPv4.class.cast(eth.getPayload());
        ICMP icmp = new ICMP();

        // The received packet should match addresses with the original sent
        assertEquals(origIp.getDestinationAddress(), ip.getSourceAddress());
        assertEquals(origIp.getSourceAddress(), ip.getDestinationAddress());
        icmp.deserialize(ByteBuffer.wrap(ip.getPayload().serialize()));

        // Let's examine the contents of the ICMP ERROR data field
        ip = new IPv4();
        ByteBuffer bb = ByteBuffer.wrap(icmp.getData());
        ip.deserializeHeader(bb);
        // This header is the NAT'd one, but should have been rev-translated
        assertEquals(origIp.getSourceAddress(), ip.getSourceAddress());
        assertEquals(origIp.getDestinationAddress(), ip.getDestinationAddress());

        if (origIp.getProtocol() == ICMP.PROTOCOL_NUMBER) {
            ICMP origIcmp = ICMP.class.cast(origIp.getPayload());
            byte[] actual = new byte[8];
            bb.get(actual, 0, 8);
            byte[] expect = Arrays.copyOfRange(origIcmp.serialize(), 0, 8);
            assertTrue(Arrays.equals(expect, actual));
        } else {
            int tpSrc = -1;
            int tpDst = -1;
            switch (origIp.getProtocol()) {
                case UDP.PROTOCOL_NUMBER:
                    UDP origUdp = UDP.class.cast(origIp.getPayload());
                    tpSrc = origUdp.getSourcePort();
                    tpDst = origUdp.getDestinationPort();
                    break;
                case TCP.PROTOCOL_NUMBER:
                    TCP origTcp = TCP.class.cast(origIp.getPayload());
                    tpSrc = origTcp.getSourcePort();
                    tpDst = origTcp.getDestinationPort();
                    break;
                default:
                    fail("Reply-to inside ICMP ERROR is not TCP/UDP/ICMP");
            }
            assertEquals(tpSrc, bb.getShort());
            assertEquals(tpDst, bb.getShort());
        }

    }


    /**
     * Sends ICMP ECHO requests from 2 VMs behind SNAT, expects them to reach
     * destination, sends replies and expects them to reach the correct VM.
     *
     * @throws Exception
     */
    @Test
    public void testSnatIcmp() throws Exception {

        setSnat();
        feedRouterARPTable();

        // Test some pings
        log.info("Send ping from private network to external IP");
        icmpArrivesNATed(
                taps[0], tapExt, vm1Mac, rtrMacPriv, vm1Ip.getAddress(),
                extIp.getAddress(), rtrIpExt.getAddress(), true);
        icmpArrivesNATed(
                taps[1], tapExt, vm2Mac, rtrMacPriv, vm2Ip.getAddress(),
                extIp.getAddress(), rtrIpExt.getAddress(), true);
        icmpArrivesNATed(
                taps[0], tapExt, vm1Mac, rtrMacPriv, vm1Ip.getAddress(),
                extIp.getAddress(), rtrIpExt.getAddress(), true);
        icmpArrivesNATed(
                taps[1], tapExt, vm2Mac, rtrMacPriv, vm2Ip.getAddress(),
                extIp.getAddress(), rtrIpExt.getAddress(), true);
    }

    /**
     * Sends ICMP ECHO requests through a DNAT rule and expects the replies to
     * reach the DNAT ip, with contents translated.
     *
     * @throws Exception
     */
    @Test
    public void testDnatIcmp() throws Exception {

        setDnat();
        feedRouterARPTable();

        // Test some pings
        log.info("Send ping from private network to external IP");
        icmpArrivesNATed(tapExt, taps[0], extMac, rtrMacExt, extIp.getAddress(),
                rtrIpExt.getAddress(), vm1Ip.getAddress(), false);
        icmpArrivesNATed(tapExt, taps[0], extMac, rtrMacExt, extIp.getAddress(),
                rtrIpExt.getAddress(), vm1Ip.getAddress(), false);
    }

    /**
     * Sends UDP from internal network to external, expects NAT'd, simulates
     * an ICMP UNREACHABLE from the ext network as response and expects
     * the error to reach the destination, with all the rev-NAT applied both
     * to the ICMP packet AND the payload.
     *
     * @throws Exception
     */
    @Test
    public void testSnatErrorAfterUdp() throws Exception {

        setSnat();
        feedRouterARPTable();

        short tpSrc = 33;
        short tpDst = 99;
        byte[] out =PacketHelper.makeUDPPacket(vm1Mac, vm1Ip.getAddress(),
                rtrMacPriv, extIp.getAddress(), tpSrc, tpDst, "meh".getBytes());

        // This is the original IP packet send from the VM
        IPv4 origIp = IPv4.class.cast(Ethernet.deserialize(out).getPayload());
        assertThat("The packet is sent.", taps[0].send(out));

        byte[] in = tapExt.recv();
        assertNotNull("The packet is received", in);
        // This is the NAT'd packet received at the external IP
        IPv4 natdIp = IPv4.class.cast(Ethernet.deserialize(in).getPayload());
        UDP udp = UDP.class.cast(natdIp.getPayload());
        assertEquals(origIp.getDestinationAddress(),
                     natdIp.getDestinationAddress());
        assertEquals(rtrIpExt.getAddress().addr(), natdIp.getSourceAddress());
        // Expect port translated to the first available
        assertEquals(1000, udp.getSourcePort());
        assertEquals(tpDst, udp.getDestinationPort());

        // Looks like the SNAT happened correctly, let's reply with an error
        log.info("UDP received {}, reply with ICMP UNREACH", natdIp);
        icmpErrorAndCheck(tapExt, taps[0], extMac, rtrMacExt, origIp, natdIp);

    }

    /**
     * Sends a PING from the private network to the external IP, replies with
     * a fake ICMP error. Expects that the ICMP ERROR's payload is rev-NAT'd
     *
     * @throws Exception
     */
    @Test
    public void testSnatIcmpErrorAfterIcmp() throws Exception {

        setSnat();
        feedRouterARPTable();

        // Send a ping from the private network
        log.info("Sending ICMP request from {} to {} ", vm2Ip, extIp);
        byte[] out = makeIcmpEchoRequest(vm2Mac, vm2Ip.getAddress(),
                                         rtrMacPriv, extIp.getAddress());
        Ethernet origEth = Ethernet.deserialize(out);
        IPv4 origIp = IPv4.class.cast(origEth.getPayload());
        ICMP origIcmp = ICMP.class.cast(origIp.getPayload());
        log.info("Sending ICMP from vm");
        assertThat("The request is sent from the source tap", taps[1].send(out));

        // Expect the packet in
        byte[] in = tapExt.recv();
        assertNotNull("The request arrives at the destination tap", in);

        // Check contents
        Ethernet eth = Ethernet.deserialize(in);
        IPv4 natdIp = IPv4.class.cast(eth.getPayload());
        ICMP icmp = ICMP.class.cast(natdIp.getPayload());
        log.info("This is the IPv4 received as request {}", natdIp);

        assertEquals(rtrIpExt.getAddress().addr(), natdIp.getSourceAddress());
        assertEquals(extIp.getAddress().addr(), natdIp.getDestinationAddress());

        assertEquals(origIcmp.getType(), icmp.getType());
        assertEquals(origIcmp.getCode(), icmp.getCode());
        assertEquals(origIcmp.getSequenceNum(), icmp.getSequenceNum());
        assertEquals(origIcmp.getIdentifier(), icmp.getIdentifier());

        // reply with an error
        log.info("ICMP reached destination, sending error");
        icmpErrorAndCheck(tapExt, taps[1], extMac, rtrMacExt, origIp, natdIp);

    }

    /**
     * Sends data via UDP to a NAT'd destination, expects it to be received,
     * then replies with an ICMP error and expects it to be rev-DNAT'd and
     * checking the original pkt inside the ICMP ERROR data field.
     *
     * @throws Exception
     */
    @Test
    public void testDnatIcmpErrorAfterUdp() throws Exception {

        setDnat();
        feedRouterARPTable();

        short tpSrc = 33;
        short tpDst = 99;
        byte[] out =PacketHelper.makeUDPPacket(extMac, extIp.getAddress(),
                                               rtrMacExt, rtrIpExt.getAddress(),
                                               tpSrc, tpDst, "meh".getBytes());

        // This is the original IP packet send from the VM
        Ethernet origEth = Ethernet.deserialize(out);
        IPv4 origIp = IPv4.class.cast(origEth.getPayload());

        log.info("Sending UDP");
        assertThat("The packet is sent.", tapExt.send(out));
        byte[] in = taps[0].recv();
        assertNotNull("The packet is received", in);
        // This is the NAT'd packet received at the VM
        IPv4 natdIp = IPv4.class.cast(Ethernet.deserialize(in).getPayload());
        assertEquals(vm1Ip.getAddress().addr(), natdIp.getDestinationAddress());
        assertEquals(extIp.getAddress().addr(), natdIp.getSourceAddress());

        // Looks like the DNAT happened correctly, let's reply with an error
        log.info("UDP packet reached destination, reply with ICMP UNREACH");
        icmpErrorAndCheck(taps[0], tapExt, vm1Mac, rtrMacPriv, origIp, natdIp);

    }

}
