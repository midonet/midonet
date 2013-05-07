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

public class VlanAwareBridgeTest extends TestBase {

    final String tenantName = "tenant-1";
    VlanBridge vlanBr;
    VlanBridgeTrunkPort trunk1;
    VlanBridgeTrunkPort trunk2;
    VlanBridgeInteriorPort vlanPort1;
    VlanBridgeInteriorPort vlanPort2;

    Bridge br1;
    Bridge br2;

    BridgePort br1IntPort = null;
    BridgePort br2IntPort = null;
    BridgePort br1ExtPort = null;
    BridgePort br2ExtPort = null;

    Host host;

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
    public void setup() {
        host = thisHost;

        // Create the two virtual bridges and plug the "vm" taps
        br1 = apiClient.addBridge().tenantId(tenantName).name("br1").create();
        br1IntPort = br1.addInteriorPort().create();
        br1ExtPort = br1.addExteriorPort().create();
        host.addHostInterfacePort()
            .interfaceName(vm1Tap.getName())
            .portId(br1ExtPort.getId()).create();

        br2 = apiClient.addBridge().tenantId(tenantName).name("br2").create();
        br2IntPort = br2.addInteriorPort().create();
        br2ExtPort = br2.addExteriorPort().create();
        host.addHostInterfacePort()
            .interfaceName(vm2Tap.getName())
            .portId(br2ExtPort.getId()).create();

        // Create the vlan-aware bridge
        vlanBr = apiClient.addVlanBridge()
                          .tenantId(tenantName)
                          .name("vlanBr1")
                          .create();

        // Create ports linking vlan-bridge with each bridge, and assign vlan id
        vlanPort1 = vlanBr.addInteriorPort().setVlanId(vlanId1).create();
        vlanPort1.link(br1IntPort.getId());

        vlanPort2 = vlanBr.addInteriorPort().setVlanId(vlanId2).create();
        vlanPort2.link(br2IntPort.getId());

        // Create the trunk ports
        trunk1 = vlanBr.addTrunkPort().create();
        trunk2 = vlanBr.addTrunkPort().create();

        host.addHostInterfacePort()
            .interfaceName(trunkTap1.getName())
            .portId(trunk1.getId())
            .create();

        host.addHostInterfacePort()
            .interfaceName(trunkTap2.getName())
            .portId(trunk2.getId())
            .create();

        Set<UUID> activePorts = new HashSet<UUID>();
        for (int i = 0; i < 4; i++) {
            LocalPortActive activeMsg = probe.expectMsgClass(
                Duration.create(10, TimeUnit.SECONDS),
                LocalPortActive.class);
            log.info("Received one LocalPortActive message from stream.");
            assertTrue("The port should be active.", activeMsg.active());
            activePorts.add(activeMsg.portID());
        }
        assertThat("The 4 ports should be active.", activePorts,
                   hasItems(trunk1.getId(), trunk2.getId(),
                            br1ExtPort.getId(), br2ExtPort.getId()));
        // All set

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            fail("W00t!! interrupted exception!!");
        }

    }

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

    @Test
    public void test() throws Exception {

        byte[] icmp = PacketHelper.makeIcmpEchoRequest(
                trunkMac, trunkIp.toIntIPv4(), vm1Mac, vm1Ip.toIntIPv4());

        log.info("Send a PING to vm1, on vlanId1");
        sendExpect(icmp, trunkTap1,  new TapWrapper[]{vm1Tap},
                   new TapWrapper[]{vm2Tap, trunkTap2},  vlanId1, true);

        log.info("Send a PING to vm2, on vlanId2");
        sendExpect(icmp, trunkTap1,  new TapWrapper[]{vm2Tap},
                   new TapWrapper[]{vm1Tap, trunkTap2},  vlanId2, true);

        icmp = PacketHelper.makeIcmpEchoRequest(
                    vm1Mac, vm1Ip.toIntIPv4(), trunkMac, trunkIp.toIntIPv4());

        log.info("Send a PING from VM1 to trunk, should have vlan1 injected");
        sendExpect(icmp, vm1Tap, new TapWrapper[]{trunkTap1, trunkTap2},
                   new TapWrapper[]{vm1Tap, vm2Tap}, vlanId1, false);

        icmp = PacketHelper.makeIcmpEchoRequest(
                    vm2Mac, vm2Ip.toIntIPv4(), trunkMac, trunkIp.toIntIPv4());

        log.info("Send a PING from VM2 to trunk, should have vlan2 injected");
        sendExpect(icmp, vm2Tap, new TapWrapper[]{trunkTap1, trunkTap2},
                   new TapWrapper[]{vm1Tap, vm2Tap}, vlanId2, false);

    }

    @Test
    public void testDataFromBothTrunks() throws Exception {

        byte[] icmpToVm1 = PacketHelper.makeIcmpEchoRequest(
                trunkMac, trunkIp.toIntIPv4(), vm1Mac, vm1Ip.toIntIPv4());
        byte[] icmpToVm2 = PacketHelper.makeIcmpEchoRequest(
                trunkMac, trunkIp.toIntIPv4(), vm2Mac, vm2Ip.toIntIPv4());
        byte[] icmpFromVm1= PacketHelper.makeIcmpEchoRequest(
                vm1Mac, vm1Ip.toIntIPv4(), trunkMac, trunkIp.toIntIPv4());
        byte[] icmpFromVm2 = PacketHelper.makeIcmpEchoRequest(
                vm2Mac, vm2Ip.toIntIPv4(), trunkMac, trunkIp.toIntIPv4());


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
