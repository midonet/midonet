package org.midonet.functional_test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import akka.util.Duration;
import org.junit.Test;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.BridgePort;
import org.midonet.client.resource.TunnelZone;
import org.midonet.client.resource.VlanBridge;
import org.midonet.client.resource.VlanBridgeInteriorPort;
import org.midonet.client.resource.VlanBridgeTrunkPort;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.topology.LocalPortActive;
import org.midonet.packets.BPDU;
import org.midonet.packets.CAPWAP;
import org.midonet.packets.Data;
import org.midonet.packets.Ethernet;
import org.midonet.packets.IPacket;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.MAC;
import org.midonet.packets.MalformedPacketException;
import org.midonet.packets.UDP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.midonet.functional_test.FunctionalTestsHelper.getEmbeddedMidolman;
import static org.midonet.functional_test.FunctionalTestsHelper.removeTapWrapper;

public class VlanAwareBridgeTwoHostsTest extends TestBase {
    private final static Logger log = LoggerFactory.getLogger(
        VlanAwareBridgeTwoHostsTest.class);

    final String TENANT_NAME = "tenant-vlan-tunnel-test";
    final String testConfigurationPath =
        "midolman_runtime_configurations/midolman-default.conf";

    // The physical network
    final IPv4Subnet physTapLocalIp = new IPv4Subnet("10.245.215.1", 24);
    final IPv4Addr physTapRemoteIp = IPv4Addr.fromString("10.245.215.2");
    final MAC physTapRemoteMac = MAC.fromString("22:aa:aa:cc:cc:cc");
    MAC physTapLocalMac = null;

    TapWrapper vmTap = new TapWrapper("vmTap");
    TapWrapper trunkTap1;
    TapWrapper physTap;

    Bridge bridge;
    UUID thisHostId, remoteHostId;

    VlanBridge vlanBr;
    VlanBridgeTrunkPort trunk1;
    VlanBridgeTrunkPort trunk2;
    VlanBridgeInteriorPort vlanPort1;

    Bridge br1;

    BridgePort br1IntPort = null;
    BridgePort br1ExtPort = null;

    short vlanId1 = 101;

    MAC trunkMac = MAC.fromString("aa:bb:cc:dd:ee:ff");

    IPv4Subnet trunkIp = new IPv4Subnet("10.1.1.10", 32);

    @Override
    protected void setup() {

        // create a tap for the physical network and populate the neighbour
        // table with the remote host's ip address, to avoid ARPing
        log.info("Creating tap for the physical network");
        physTap = new TapWrapper("physTap");
        physTap.setIpAddress(physTapLocalIp);
        physTap.addNeighbour(physTapRemoteIp, physTapRemoteMac);
        physTapLocalMac = physTap.getHwAddr();
        assertNotNull("the physical tap's hw address", physTapLocalMac);


        log.info("Creating tap for the trunk1");
        trunkTap1 = new TapWrapper("trunk1");
        trunkTap1.setIpAddress(trunkIp);
        trunkMac = physTap.getHwAddr();
        assertNotNull("The physical tap's HW address", trunkTap1);

        // through the data client:
        log.info("Creating remote host");
        remoteHostId = UUID.randomUUID();
        org.midonet.cluster.data.host.Host remoteHost;
        remoteHost = new org.midonet.cluster.data.host.Host();
        try {
            InetAddress[] tmp = {
                InetAddress.getByName(physTapRemoteIp.toString()) };
            remoteHost.setName("remoteHost")
                      .setId(remoteHostId)
                      .setIsAlive(true)
                      .setAddresses(tmp);
        } catch (UnknownHostException e) {
            log.error("Error creating host {}", e);
            fail("Cannot create host");
        }

        try {
            getEmbeddedMidolman().getDataClient()
                                 .hostsCreate(remoteHostId, remoteHost);
        } catch (StateAccessException e) {
            log.error("Error creating host {}", e);
            fail("Cannot create host");
        } catch (SerializationException e) {
            log.error("Cannot create add port mappings due"
                    + " to serialization error", e);
        }

        // Create a capwap tunnel zone
        log.info("Creating tunnel zone.");
        TunnelZone tunnelZone = apiClient.addCapwapTunnelZone()
                                         .name("CapwapZone").create();

        // add this host to the capwap zone
        thisHostId = thisHost.getId();
        log.info("Adding this host {} to tunnel zone.", thisHostId);
        tunnelZone.addTunnelZoneHost().
            hostId(thisHostId).
                      ipAddress(physTapLocalIp.getAddress().toString()).
                      create();

        log.info("Adding remote host to tunnelzone");
        tunnelZone.addTunnelZoneHost()
                  .hostId(remoteHostId)
                  .ipAddress(physTapRemoteIp.toString()).create();

        // Create the two virtual bridges and plug the "vm" taps
        br1 = apiClient.addBridge().tenantId(TENANT_NAME).name("br1").create();
        br1IntPort = br1.addInteriorPort().create();
        br1ExtPort = br1.addExteriorPort().create();
        thisHost.addHostInterfacePort()
                .interfaceName(vmTap.getName())
                .portId(br1ExtPort.getId()).create();

        // Create the vlan-aware bridge
        vlanBr = apiClient.addVlanBridge()
                          .tenantId(TENANT_NAME)
                          .name("vlanBr1")
                          .create();

        // Create ports linking vlan-bridge with each bridge, and assign vlan id
        vlanPort1 = vlanBr.addInteriorPort().setVlanId(vlanId1).create();
        vlanPort1.link(br1IntPort.getId());

        // Create the trunk ports
        trunk1 = vlanBr.addTrunkPort().create();
        trunk2 = vlanBr.addTrunkPort().create();

        thisHost.addHostInterfacePort()
            .interfaceName(trunkTap1.getName())
            .portId(trunk1.getId())
            .create();

        log.info("binding trunk port to remote host");
        try {
            getEmbeddedMidolman().getDataClient().hostsAddVrnPortMapping(
                remoteHostId, trunk2.getId(), "physTap");

            getEmbeddedMidolman().getDataClient().hostsAddVrnPortMapping(
                thisHostId, trunk1.getId(), "trunk1");
        } catch (StateAccessException e) {
            log.error("Cannot create add port mappings", e);
        } catch (SerializationException e) {
            log.error("Cannot create add port mappings due"
                      + " to serialization error", e);
        }

        // We have 2 local ports: on a bridge, and one trunk
        Set<UUID> activePorts = new HashSet<UUID>();
        for (int i = 0; i < 2; i++) {
            LocalPortActive activeMsg = probe.expectMsgClass(
                Duration.create(10, TimeUnit.SECONDS),
                LocalPortActive.class);
            log.info("Received one LocalPortActive message from stream.");
            assertTrue("The port should be active.", activeMsg.active());
            activePorts.add(activeMsg.portID());
        }
        assertThat("The 3 ports should be active.", activePorts,
                   hasItems(trunk1.getId(), br1ExtPort.getId()));
    }

    @Override
    protected void teardown() {
        removeTapWrapper(vmTap);
        removeTapWrapper(physTap);
    }

    @Test
    public void testBPDU() throws MalformedPacketException {

        byte[] bpdu = PacketHelper.makeBPDU(
            trunkMac, MAC.fromString("01:80:c2:00:00:00"),
            BPDU.MESSAGE_TYPE_TCNBPDU, (byte)0x0, 100, 10, 1, (short)23,
            (short)1000, (short)2340, (short)100, (short)10);

        log.info("Sending BPDU frame from trunk 1, expecting at trunk 2, which is" +
                     "remote");
        trunkTap1.send(bpdu);

        IPacket encap = matchTunnelPacket(physTap,
                physTapLocalMac, physTapLocalIp.getAddress(),
                physTapRemoteMac, physTapRemoteIp);
        Ethernet eth = (Ethernet) encap;
        assertEquals(eth.getEtherType(), BPDU.ETHERTYPE);

        bpdu = PacketHelper.makeBPDU(
            trunkMac, MAC.fromString("01:80:c2:00:00:00"),
            BPDU.MESSAGE_TYPE_TCNBPDU, (byte)0x0, 100, 10, 1, (short)23,
            (short)1000, (short)2340, (short)100, (short)10);

        CAPWAP capwap = new CAPWAP();
        capwap.setPayload(Ethernet.deserialize(bpdu));
        byte[] capwapBytes = capwap.serialize();
        physTap.send(capwapBytes);
        byte[] in = trunkTap1.recv();
        assertArrayEquals("The frame should match", bpdu, in);

        /*

        byte[] in = physTap.recv();
        Assert.assertNotNull("Trunk 2 didn't receive BPDU", in);
        assertArrayEquals(String.format("Data at trunk 2 doesn't match " +
                                            "expected %s, was %s", bpdu, in),
                          bpdu, in);

        trunkTap2.send(bpdu);
        in = trunkTap1.recv();
        Assert.assertNotNull("Trunk 1 didn't receive BPDU", in);
        assertArrayEquals(String.format("Data at trunk 1 doesn't match " +
                                            "expected %s, was %s", bpdu, in),
                          bpdu, in);           */
    }


    protected IPacket matchTunnelPacket(TapWrapper device,
                                        MAC fromMac, IPv4Addr fromIp,
                                        MAC toMac, IPv4Addr toIp)
        throws MalformedPacketException {
        byte[] received = device.recv();
        assertNotNull(String.format("Expected packet on %s", device.getName()),
                      received);

        Ethernet eth = Ethernet.deserialize(received);
        log.info("got packet on " + device.getName() + ": " + eth.toString());

        assertEquals("source ethernet address",
                     fromMac, eth.getSourceMACAddress());
        assertEquals("destination ethernet address",
                     toMac, eth.getDestinationMACAddress());
        assertEquals("ethertype", IPv4.ETHERTYPE, eth.getEtherType());

        assertTrue("payload is IPv4", eth.getPayload() instanceof IPv4);
        IPv4 ipPkt = (IPv4) eth.getPayload();
        assertEquals("source ipv4 address",
                     fromIp.addr(), ipPkt.getSourceAddress());
        assertEquals("destination ipv4 address",
                     toIp.addr(), ipPkt.getDestinationAddress());

        assertTrue("payload is UDP", ipPkt.getPayload() instanceof UDP);
        UDP udpPkt = (UDP) ipPkt.getPayload();
        assertTrue("payload is Data", udpPkt.getPayload() instanceof Data);
        Data data = (Data) udpPkt.getPayload();

        CAPWAP capwap = new CAPWAP();
        capwap.deserialize(ByteBuffer.wrap(data.serialize()));

        return capwap.getPayload();
    }

}
