/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.functional_test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import akka.util.Duration;
import org.junit.Test;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.BridgePort;
import org.midonet.client.resource.Host;
import org.midonet.client.resource.VlanBridge;
import org.midonet.client.resource.VlanBridgeInteriorPort;
import org.midonet.client.resource.VlanBridgeTrunkPort;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.midolman.topology.LocalPortActive;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.packets.BPDU;
import org.midonet.packets.MalformedPacketException;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.fail;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * This test provides the basic tests for functionality related to VlanAware
 * bridges. We have two devices that implement it, the VlanAwareBridge and the
 * new Bridge. Two implementing classes build specific topologies based on these
 * devices and let this class run the tests.
 *
 * The topology is expected to have:
 * - Two normal bridges, each connected to a tap vm1Tap and vm2Tap
 * - A vlan-aware bridge with
 *   - two trunks bound to trunkTap1 and trunkTap2
 *   - two interior ports linked to the vlan-unaware bridges, each with
 *     a vlanId
 *
 * The variables below are self-explanatory on what exact connections need to
 * be made. Bridge1 has vm1Tap, with vm1Ip, vm1Mac, and connected to the vlan
 * aware bridge on a port with vlanId1. Same with 2's.
 *
 * The implementing tests responsible to initialize MM and leave things ready
 * for actual simulations. They are free to add more test cases with specifics
 * related to each device (e.g.: the enhanced Bridge supports mac learning)
 */
abstract public class VlanAwareBridgeTest extends TestBase {

    final String tenantName = "tenant-1";
    TapWrapper trunkTap1 = new TapWrapper("vbtestTrunk1");
    TapWrapper trunkTap2 = new TapWrapper("vbtestTrunk2");
    TapWrapper vm1Tap = new TapWrapper("vm1Tap");
    TapWrapper vm2Tap = new TapWrapper("vm2Tap");

    short vlanId1 = 101;
    short vlanId2 = 202;

    MAC trunkMac = MAC.fromString("aa:bb:cc:dd:ee:ff");
    MAC vm1Mac = MAC.fromString("aa:bb:aa:bb:cc:cc");
    MAC vm2Mac = MAC.fromString("cc:cc:aa:aa:bb:bb");

    IPv4Addr trunkIp = IPv4Addr.fromString("10.1.1.10");
    IPv4Addr vm1Ip = IPv4Addr.fromString("10.1.1.1");
    IPv4Addr vm2Ip = IPv4Addr.fromString("10.1.1.2");

    @Override
    public void teardown() {
        trunkTap1.remove();
        trunkTap2.remove();
        vm1Tap.remove();
        vm2Tap.remove();
    }

    private void sendExpect(byte[] pkt, TapWrapper from, TapWrapper[] to,
                            TapWrapper[] notTo, short vlanId, 
                            boolean injectVlanOnSend) 
            throws MalformedPacketException {

        byte[] outPkt = (injectVlanOnSend) ? PacketHelper.addVlanId(pkt, vlanId)
                        : pkt;
        byte[] expPkt = (injectVlanOnSend) ? pkt
                        : PacketHelper.addVlanId(pkt, vlanId);

        from.send(outPkt);

        for (TapWrapper t : to) {
            byte[] in = t.recv();
            assertNotNull(String.format("Tap %s didn't receive data",
                                        t.getName()), in);
            assertArrayEquals(String.format("Data at tap %s doesn't match " + 
                                      "expected %s, was %s", t.getName(),
                                      expPkt, in),
                              in, expPkt);
        }

        for (TapWrapper t : notTo) {
            byte[] in = t.recv();
            assertNull(String.format("%s should NOT have data", t.getName()), 
                       in);
        }

    }

    /**
     * Implement as appropriate. This will have a main visible implication in
     * the tests: when data must go to trunks (e.g.: a) a unicast from the vNw
     * or b) a multicast from a trunk such as an ARP request), if mac-learning
     * is enabled a) the frame will go to a single trunk if its mac is learned
     * and b) the frame will be sent to the interior port and also to the
     * other trunks - in the VAB implementation that doesn't have MAC learning
     * it just goes to the internal nw.
     */
    abstract protected boolean hasMacLearning();

    @Test
    public void test() throws Exception {

        byte[] icmp = PacketHelper.makeIcmpEchoRequest(
                trunkMac, trunkIp, vm1Mac, vm1Ip);

        log.info("Send a PING to vm1, on vlanId1");
        sendExpect(icmp, trunkTap1,  new TapWrapper[]{vm1Tap},
                   new TapWrapper[]{vm2Tap, trunkTap2},  vlanId1, true);

        log.info("Send a PING to vm2, on vlanId2");
        sendExpect(icmp, trunkTap1,  new TapWrapper[]{vm2Tap},
                   new TapWrapper[]{vm1Tap, trunkTap2},  vlanId2, true);

        icmp = PacketHelper.makeIcmpEchoRequest(
                    vm1Mac, vm1Ip, trunkMac, trunkIp);

        log.info("Send a PING from VM1 to trunk, should have vlan1 injected");
        sendExpect(icmp, vm1Tap, new TapWrapper[]{trunkTap1, trunkTap2},
                   new TapWrapper[]{vm1Tap, vm2Tap}, vlanId1, false);

        icmp = PacketHelper.makeIcmpEchoRequest(
                    vm2Mac, vm2Ip, trunkMac, trunkIp);

        log.info("Send a PING from VM2 to trunk, should have vlan2 injected");
        sendExpect(icmp, vm2Tap, new TapWrapper[]{trunkTap1, trunkTap2},
                   new TapWrapper[]{vm1Tap, vm2Tap}, vlanId2, false);

    }

    @Test
    public void testDataFromBothTrunks() throws Exception {

        byte[] icmpToVm1 = PacketHelper.makeIcmpEchoRequest(
                trunkMac, trunkIp, vm1Mac, vm1Ip);
        byte[] icmpToVm2 = PacketHelper.makeIcmpEchoRequest(
                trunkMac, trunkIp, vm2Mac, vm2Ip);
        byte[] icmpFromVm1= PacketHelper.makeIcmpEchoRequest(
                vm1Mac, vm1Ip, trunkMac, trunkIp);
        byte[] icmpFromVm2 = PacketHelper.makeIcmpEchoRequest(
                vm2Mac, vm2Ip, trunkMac, trunkIp);


        // This will also get ARP tables ready
        log.info("Send a PING to vm1, on vlanId1");
        sendExpect(icmpToVm1, trunkTap1, 
                   new TapWrapper[]{vm1Tap}, 
                   new TapWrapper[]{vm2Tap, trunkTap2}, 
                   vlanId1, true);
        log.info("Send a PING to vm2, on vlanId2");
        sendExpect(icmpToVm2, trunkTap1, 
                   new TapWrapper[]{vm2Tap}, 
                   new TapWrapper[]{vm1Tap, trunkTap2}, 
                   vlanId2, true);

        // Current active trunk is 1

        log.info("Send PINGs from VMs");
        for (int i = 0; i < 3; i++) {
            // vm1 pings something
            sendExpect(icmpFromVm1, vm1Tap,
                       new TapWrapper[]{trunkTap1, trunkTap2},
                       new TapWrapper[]{vm1Tap, vm2Tap},
                       vlanId1, false);
            // vm2 pings something on the trunk
            sendExpect(icmpFromVm2, vm2Tap,
                       new TapWrapper[]{trunkTap1, trunkTap2},
                       new TapWrapper[]{vm1Tap, vm2Tap},
                       vlanId2, false);
            // simulate responses from the trunk
            sendExpect(icmpToVm1, trunkTap1, 
                       new TapWrapper[]{vm1Tap},
                       new TapWrapper[]{vm2Tap, trunkTap2},
                       vlanId1, true);
            // simulate responses from the trunk
            sendExpect(icmpToVm2, trunkTap1, 
                       new TapWrapper[]{vm2Tap}, 
                       new TapWrapper[]{vm1Tap, trunkTap2}, 
                       vlanId2, true);
        }

        // The other trunk has data now
        sendExpect(icmpToVm1, trunkTap2,
                       new TapWrapper[]{vm1Tap}, 
                       new TapWrapper[]{vm2Tap, trunkTap1}, 
                       vlanId1, true);

        // Give MM some time to react to the takeover
        Thread.sleep(500);

        log.info("Send PINGs from VMs to trunks");
        for (int i = 0; i < 3; i++) {
            // vm1 pings something on the trunk
            sendExpect(icmpFromVm1, vm1Tap,
                       new TapWrapper[]{trunkTap1, trunkTap2},
                       new TapWrapper[]{vm1Tap, vm2Tap},
                       vlanId1, false);
            // vm2 pings something on the trunk
            sendExpect(icmpFromVm2, vm2Tap,
                       new TapWrapper[]{trunkTap2, trunkTap1},
                       new TapWrapper[]{vm1Tap, vm2Tap},
                       vlanId2, false);
            // simulate responses from the trunk
            sendExpect(icmpToVm1, trunkTap2, 
                       new TapWrapper[]{vm1Tap}, 
                       new TapWrapper[]{vm2Tap, trunkTap1}, 
                       vlanId1, true);
            // simulate responses from the trunk
            sendExpect(icmpToVm2, trunkTap2, 
                       new TapWrapper[]{vm2Tap}, 
                       new TapWrapper[]{vm1Tap, trunkTap1}, 
                       vlanId2, true);
        }

        log.info("All ok!");

    }

    /**
     * Tests sending BPDUs accross the trunk ports.
     * @throws Exception
     */
    @Test
    public void testBPDU() throws Exception {

        byte[] bpdu1 = PacketHelper.makeBPDU(
            vm1Mac, MAC.fromString("01:80:c2:00:00:00"),
            BPDU.MESSAGE_TYPE_TCNBPDU, (byte)0x0, 100, 10, 1, (short)23,
            (short)1000, (short)2340, (short)100, (short)10);

        trunkTap1.send(bpdu1);
        byte[] in = trunkTap2.recv();
        assertNotNull("Trunk 2 didn't receive BPDU", in);
        assertArrayEquals(String.format("Data at trunk 2 doesn't match " +
                                        "expected %s, was %s", bpdu1, in),
                          bpdu1, in);

        byte[] bpdu2 = PacketHelper.makeBPDU(
            vm2Mac, MAC.fromString("01:80:c2:00:00:00"),
            BPDU.MESSAGE_TYPE_TCNBPDU, (byte)0x0, 100, 10, 1, (short)23,
            (short)1000, (short)2340, (short)100, (short)10);

        trunkTap2.send(bpdu2);
        in = trunkTap1.recv();
        assertNotNull("Trunk 1 didn't receive BPDU", in);
        assertArrayEquals(String.format("Data at trunk 1 doesn't match " +
                                        "expected %s, was %s", bpdu2, in),
                          bpdu2, in);
    }

}
