/*
 * Copyright 2011 Midokura Europe SARL
 */

package org.midonet.functional_test;

import java.io.File;
import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import akka.testkit.TestProbe;
import akka.util.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.topology.LocalPortActive;
import org.midonet.client.MidonetApi;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.BridgePort;
import org.midonet.client.resource.Host;
import org.midonet.client.resource.ResourceCollection;
import org.midonet.cluster.DataClient;
import org.midonet.functional_test.utils.EmbeddedMidolman;
import org.midonet.functional_test.utils.TapWrapper;
import org.midonet.packets.IPacket;
import org.midonet.packets.MAC;
import org.midonet.packets.MalformedPacketException;


import static org.midonet.functional_test.FunctionalTestsHelper.*;
import static org.junit.Assert.assertNotNull;


public abstract class BaseTunnelTest {

    private final static Logger log = LoggerFactory.getLogger(BaseTunnelTest.class);

    final String TENANT_NAME = "tenant-tunnel-test";
    final String testConfigurationPath =
            "midolman_runtime_configurations/midolman-default.conf";

    // The two VMs that will send traffic across the bridge
    final IPv4Subnet localVmIp = new IPv4Subnet("192.168.231.1", 24);
    final IPv4Subnet remoteVmIp = new IPv4Subnet("192.168.231.2", 24);
    final MAC localVmMac = MAC.fromString("22:55:55:11:11:11");
    final MAC remoteVmMac = MAC.fromString("22:33:33:44:44:44");
    // The physical network
    final IPv4Subnet physTapLocalIp = new IPv4Subnet("10.245.215.1", 24);
    final IPv4Addr physTapRemoteIp = IPv4Addr.fromString("10.245.215.2");
    final MAC physTapRemoteMac = MAC.fromString("22:aa:aa:cc:cc:cc");
    MAC physTapLocalMac = null;

    TapWrapper vmTap, physTap;
    BridgePort localPort, remotePort;
    Bridge bridge;
    Host thisHost, remoteHost;
    UUID thisHostId, remoteHostId;

    ApiServer apiStarter;
    MidonetApi apiClient;
    EmbeddedMidolman midolman;

    DataClient dataClient;

    private static final String TEST_HOST_ID = "910de343-c39b-4933-86c7-540225fb02f9";

    @Before
    public void setUp() throws Exception {
        File testConfigFile = new File(testConfigurationPath);
        log.info("Starting embedded zookeper");
        int zkPort = startEmbeddedZookeeper(testConfigurationPath);
        log.info("Starting cassandra");
        startCassandra();
        log.info("Starting REST API");
        apiStarter = new ApiServer(zkPort);
        apiClient = new MidonetApi(apiStarter.getURI());
        log.info("Starting midolman");
        midolman = startEmbeddedMidolman(testConfigFile.getAbsolutePath());
        dataClient = midolman.getDataClient();

        TestProbe probe = new TestProbe(midolman.getActorSystem());
        midolman.getActorSystem().eventStream().subscribe(
                probe.ref(), LocalPortActive.class);

        // Create a bridge with two ports
        log.info("Creating bridge and two ports.");
        bridge = apiClient.addBridge().tenantId(TENANT_NAME).name("br1").create();
        localPort = bridge.addExteriorPort().create();
        remotePort = bridge.addExteriorPort().create();

        ResourceCollection<Host> hosts = apiClient.getHosts();
        thisHost = null;
        for (Host h : hosts) {
            if (h.getId().toString().matches(TEST_HOST_ID)) {
                thisHost = h;
                thisHostId = h.getId();
            }
        }
        // check that we've actually found the test host.
        assertNotNull(thisHost);

        // create the tap for the vm and bind it to this host
        log.info("Creating tap for local vm and binding port to it");
        vmTap = new TapWrapper("vmTap");
        thisHost.addHostInterfacePort()
                .interfaceName(vmTap.getName())
                .portId(localPort.getId()).create();

        probe.expectMsgClass(Duration.create(10, TimeUnit.SECONDS),
                             LocalPortActive.class);

        // create a tap for the physical network and populate the neighbour
        // table with the remote host's ip address, to avoid ARPing
        log.info("Creating tap for the physical network");
        physTap = new TapWrapper("physTap");
        physTap.setIpAddress(physTapLocalIp);
        physTap.addNeighbour(physTapRemoteIp, physTapRemoteMac);
        physTapLocalMac = physTap.getHwAddr();
        assertNotNull("the physical tap's hw address", physTapLocalMac);

        // through the data client:
        log.info("Creating remote host");
        remoteHostId = UUID.randomUUID();
        org.midonet.cluster.data.host.Host remoteHost;
        remoteHost = new org.midonet.cluster.data.host.Host();
        InetAddress[] tmp =
            { InetAddress.getByName(physTapRemoteIp.toString()) };
        remoteHost.setName("remoteHost").
                setId(remoteHostId).
                setIsAlive(true).
                setAddresses(tmp);
        dataClient.hostsCreate(remoteHostId, remoteHost);

        log.info("binding remote port to remote host");
        dataClient.hostsAddVrnPortMapping(
                remoteHostId, remotePort.getId(), "nonExistent");

        log.info("adding remote host to portSet");
        dataClient.portSetsAddHost(bridge.getId(), remoteHostId);

        setUpTunnelZone();

        // TODO
        //sleepBecause("we need the network to boot up", 10);
    }

    @After
    public void tearDown() throws Exception {
        removeTapWrapper(vmTap);
        removeTapWrapper(physTap);
        stopEmbeddedMidolman();
        apiStarter.stop();
        stopCassandra();
        stopEmbeddedZookeeper();
    }

    protected abstract IPacket matchTunnelPacket(TapWrapper device,
                                                 MAC fromMac, IPv4Addr fromIp,
                                                 MAC toMac, IPv4Addr toIp)
                                            throws MalformedPacketException;

    private void sendToTunnelAndVerifyEncapsulation()
            throws MalformedPacketException {
        byte[] pkt = PacketHelper.makeUDPPacket(
                localVmMac, localVmIp.getAddress(),
                remoteVmMac, remoteVmIp.getAddress(),
                (short) 2345, (short) 9876, "The Payload".getBytes());
        assertPacketWasSentOnTap(vmTap, pkt);

        log.info("Waiting for packet on physical tap");
        IPacket encap = matchTunnelPacket(physTap,
                physTapLocalMac, physTapLocalIp.getAddress(),
                physTapRemoteMac, physTapRemoteIp);
        PacketHelper.matchUdpPacket(encap, localVmMac, localVmIp.getAddress(),
                              remoteVmMac, remoteVmIp.getAddress(),
                              (short) 2345, (short) 9876);
    }

    private void sendFromTunnelAndVerifyDecapsulation(byte[] pkt)
            throws MalformedPacketException {
        log.info("Injecting packet on physical tap");
        assertPacketWasSentOnTap(physTap, pkt);

        log.info("Waiting for packet on vm tap");
        PacketHelper.matchUdpPacket(vmTap, remoteVmMac, remoteVmIp.getAddress(),
                              localVmMac, localVmIp.getAddress(),
                              (short) 9876, (short) 2345);
    }

    @Test
    public void testTunnel() throws MalformedPacketException {
        // sent two packets through the tunnel
        sendToTunnelAndVerifyEncapsulation();
        sendToTunnelAndVerifyEncapsulation();

        // send two packets from the other side of the tunnel, to the port set
        sendFromTunnelAndVerifyDecapsulation(buildEncapsulatedPacketForPortSet());
        sendFromTunnelAndVerifyDecapsulation(buildEncapsulatedPacketForPortSet());

        // send two packets from the other side of the tunnel, to the port
        sendFromTunnelAndVerifyDecapsulation(buildEncapsulatedPacketForPort());
        //sendFromTunnelAndVerifyDecapsulation(buildEncapsulatedPacketForPort());
    }

    protected abstract void setUpTunnelZone() throws Exception;

    protected abstract byte[] buildEncapsulatedPacketForPortSet();

    protected abstract byte[] buildEncapsulatedPacketForPort();

    protected void writeOnPacket(byte[] pkt, byte[] data, int offset) {
        for (int i = 0; i < data.length; i++)
            pkt[offset+i] = data[i];
    }
}
