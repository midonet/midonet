/*
 * Copyright 2011 Midokura Europe SARL
 */
package org.midonet.functional_test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.lang.String.format;

import org.midonet.client.dto.PortType;
import org.midonet.client.resource.Bridge;
import org.midonet.client.resource.BridgePort;
import org.midonet.client.resource.Host;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.MidolmanModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.functional_test.utils.*;

import akka.testkit.TestProbe;
import akka.util.Duration;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;

import org.apache.commons.io.FileUtils;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.midonet.midolman.topology.LocalPortActive;
import org.midonet.packets.IPv4Addr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertTrue;
import static org.testng.Assert.assertEquals;

import org.midonet.functional_test.vm.VMController;
import org.midonet.packets.Ethernet;
import org.midonet.packets.LLDP;
import org.midonet.packets.LLDPTLV;
import org.midonet.packets.MAC;
import org.midonet.packets.MalformedPacketException;
import org.midonet.util.SystemHelper;
import org.midonet.util.process.ProcessHelper;

// TODO marc this class should be converted to a base class for all the functional tests:
public class FunctionalTestsHelper {

    public static final String LOCK_NAME = "functional-tests";

    private static EmbeddedMidolman midolman;

    protected final static Logger log = LoggerFactory
            .getLogger(FunctionalTestsHelper.class);

    protected static void cleanupZooKeeperData()
        throws IOException, InterruptedException {
        startZookeeperService();
    }

    protected static void stopZookeeperService(ZKLauncher zkLauncher)
            throws IOException, InterruptedException {
        if ( zkLauncher != null ) {
           zkLauncher.stop();
        }
    }

    protected static void removeCassandraFolder() throws IOException, InterruptedException {
        File cassandraFolder = new File("target/cassandra");
        FileUtils.deleteDirectory(cassandraFolder);
    }

    protected static void stopZookeeperService() throws IOException, InterruptedException {
        ProcessHelper.newLocalProcess("service zookeeper stop").withSudo().runAndWait();
    }

    protected static void startZookeeperService() throws IOException, InterruptedException {
        ProcessHelper.newLocalProcess("service zookeeper start").withSudo().runAndWait();
    }

    protected static String getZkClient() {
        // Often zkCli.sh is not in the PATH, use the one from default install
        // otherwise
        String zkClientPath;
        SystemHelper.OsType osType = SystemHelper.getOsType();

        switch (osType) {
            case Mac:
                zkClientPath = "zkCli";
                break;
            case Linux:
            case Unix:
            case Solaris:
                zkClientPath = "zkCli.sh";
                break;
            default:
                zkClientPath = "zkCli.sh";
                break;
        }

        List<String> pathList =
                ProcessHelper.executeLocalCommandLine("which " + zkClientPath).consoleOutput;

        if (pathList.isEmpty()) {
            switch (osType) {
                case Mac:
                    zkClientPath = "/usr/local/bin/zkCli";
                    break;
                default:
                    zkClientPath = "/usr/share/zookeeper/bin/zkCli.sh";
            }
        }
        return zkClientPath;
    }

    protected static void cleanupZooKeeperServiceData()
        throws IOException, InterruptedException {
        cleanupZooKeeperServiceData(null);
    }

    protected static void cleanupZooKeeperServiceData(ZKLauncher.ConfigType configType)
            throws IOException, InterruptedException {

        String zkClient = getZkClient();

        int port = 2181;
        if (configType != null) {
            port = configType.getPort();
        }

        //TODO(pino, mtoader): try removing the ZK directory without restarting
        //TODO:     ZK. If it fails, stop/start/remove, to force the remove,
        //TODO      then throw an error to identify the bad test.

        int exitCode = ProcessHelper
                .newLocalProcess(
                    format("%s -server 127.0.0.1:%d rmr /smoketest",
                           zkClient, port))
                .logOutput(log, "cleaning_zk")
                .runAndWait();

        if (exitCode != 0 && SystemHelper.getOsType() == SystemHelper.OsType.Linux) {
            // Restart ZK to get around the bug where a directory cannot be deleted.
            stopZookeeperService();
            startZookeeperService();

            // Now delete the functional test ZK directory.
            ProcessHelper
                    .newLocalProcess(
                        format(
                            "%s -server 127.0.0.1:%d rmr /smoketest",
                            zkClient, port))
                    .logOutput(log, "cleaning_zk")
                    .runAndWait();
        }
    }

    public static void removeRemoteTap(RemoteTap tap) {
        if (tap != null) {
            try {
                tap.remove();
            } catch (Exception e) {
                log.error("While trying to remote a remote tap", e);
            }
        }
    }

    public static void removeTapWrapper(TapWrapper tap) {
        if (tap != null) {
            tap.remove();
        }
    }

    public static void fixQuaggaFolderPermissions()
            throws IOException, InterruptedException {
        // sometimes after a reboot someone will reset the permissions which in
        // turn will make our Zebra implementation unable to bind to the socket
        // so we fix it like a boss.
        ProcessHelper
                .newProcess("chmod 777 /var/run/quagga")
                .withSudo()
                .runAndWait();
    }

    public static void destroyVM(VMController vm) {
        try {
            if (vm != null) {
                vm.destroy();
            }

            Thread.sleep(2000);
        } catch (InterruptedException e) {
            //
        }
    }

    public static void assertNoMorePacketsOnTap(TapWrapper tapWrapper) {
        assertThat(
                format("Got an unexpected packet from tap %s",
                        tapWrapper.getName()),
                tapWrapper.recv(), nullValue());
    }

    public static void assertNoMorePacketsOnTaps(TapWrapper[] tapWrappers) {
        for (TapWrapper tapWrapper : tapWrappers) {
            assertNoMorePacketsOnTap(tapWrapper);
        }
    }

    public static void assertNoMorePacketsOnTap(RemoteTap tap) {
        assertThat(
                format("Got an unexpected packet from tap %s", tap.getName()),
                tap.recv(), nullValue());
    }

    public static void assertPacketWasSentOnTap(TapWrapper tap,
                                                byte[] packet) {
        assertThat(
                format("We couldn't send a packet via tap %s", tap.getName()),
                tap.send(packet));
    }

    public static void assertPacketWasSentOnTap(RemoteTap tap,
                                                byte[] packet) {
        assertThat(
                format("We couldn't send a packet via tap %s", tap.getName()),
                tap.send(packet));

    }

    /**
     * Starts an embedded zookeeper.
     * This method reads the configuration file to discover in which port the embedded zookeeper should run.
     * @param configurationFile Path of the test configuration file.
     * @return
     */
    public static int startEmbeddedZookeeper(String configurationFile) {

        // read the midolman configuration file.
        File testConfFile = new File(configurationFile);

        if (!testConfFile.exists()) {
            log.error("Configuration file does not exist: {}", configurationFile);
            return -1;
        }

        List<Module> modules = new ArrayList<Module>();
        modules.add( new AbstractModule() {
             @Override
             protected void configure() {
                 bind(MidolmanConfig.class)
                         .toProvider(MidolmanModule.MidolmanConfigProvider.class)
                         .asEagerSingleton();
             }
        });

        modules.add(new ConfigProviderModule(configurationFile));
        Injector injector = Guice.createInjector(modules);
        MidolmanConfig mConfig = injector.getInstance(MidolmanConfig.class);

        // get the zookeeper port from the configuration.
        String hostsLine = mConfig.getZooKeeperHosts();
        // if there are more than one zookeeper host, get the first one.
        String zookeeperHostLine = hostsLine.split(",")[0];
        int zookeperPort = Integer.parseInt(zookeeperHostLine.split(":")[1]);
        String zookeeperHost = zookeeperHostLine.split(":")[0];
        assertThat("Zookeeper host should be the local machine",
          (zookeeperHost.matches("127.0.0.1") || zookeeperHost.matches("localhost")));

        log.info("Starting zookeeper at port: " + zookeperPort);
        return EmbeddedZKLauncher.start(zookeperPort);
    }

    public static void stopEmbeddedZookeeper() {
        EmbeddedZKLauncher.stop();
    }



    /**
     * Start an embedded cassandra with the default configuration.
     */
    public static void startCassandra() {
        try {
            EmbeddedCassandraServerHelper.startEmbeddedCassandra();
        } catch (Exception e) {
            log.error("Failed to start embedded Cassandra.", e);
        }
    }

    public static void stopCassandra() {
        //Don't stop it because it cannot be restarted (bug in stop method).
        EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
    }

    /**
     * Stops an embedded midoalman.
     */
    public static void stopEmbeddedMidolman() {
        if (midolman != null)
            midolman.stopMidolman();
    }

    /**
     * Starts an embedded midolman with the given configuratin file.
     * @param configFile
     */
    public static EmbeddedMidolman startEmbeddedMidolman(String configFile) {
        midolman = new EmbeddedMidolman();
        try {
            midolman.startMidolman(configFile);
        } catch (Exception e) {
            log.error("Could not start Midolmanj", e);
        }
        // TODO wait until the agents are started.
        return midolman;
    }

    public static EmbeddedMidolman getEmbeddedMidolman() {
        return midolman;
    }

    /**
     * Stops midolman (as a separate process)
     * @param ml
     */
    public static void stopMidolman(MidolmanLauncher ml) {
        ml.stop();
    }

    public static byte[] icmpFromTapArrivesAtTap(
        TapWrapper tapSrc, TapWrapper tapDst,
        MAC dlSrc, MAC dlDst, IPv4Addr ipSrc, IPv4Addr ipDst) {
        byte[] pkt = PacketHelper.makeIcmpEchoRequest(
            dlSrc, ipSrc, dlDst, ipDst);
        assertThat("The packet should have been sent from the source tap.",
            tapSrc.send(pkt));
        assertThat("The packet should have arrived at the destination tap.",
                   tapDst.recv(), allOf(notNullValue(), equalTo(pkt)));
        return pkt;
    }

    public static byte[] icmpFromTapDoesntArriveAtTap(
        TapWrapper tapSrc, TapWrapper tapDst,
        MAC dlSrc, MAC dlDst, IPv4Addr ipSrc, IPv4Addr ipDst) {
        byte[] pkt = PacketHelper.makeIcmpEchoRequest(
            dlSrc, ipSrc, dlDst, ipDst);
        assertThat("The packet should have been sent from the source tap.",
            tapSrc.send(pkt));
        assertThat("The packet should not have arrived at the destination tap.",
            tapDst.recv(), nullValue());
        return pkt;
    }

    public static void udpFromTapArrivesAtTap(TapWrapper tapSrc, TapWrapper tapDst,
                                       MAC dlSrc, MAC dlDst, IPv4Addr ipSrc, IPv4Addr ipDst,
                                       short tpSrc, short tpDst, byte[] payload) {
        byte[] pkt = PacketHelper.makeUDPPacket(
            dlSrc, ipSrc, dlDst, ipDst, tpSrc, tpDst, payload);
        assertThat("The packet should have been sent from the source tap.",
                   tapSrc.send(pkt));
        assertThat("The packet should have arrived at the destination tap.",
                   tapDst.recv(), allOf(notNullValue(), equalTo(pkt)));
    }

    public static void udpFromTapDoesntArriveAtTap(TapWrapper tapSrc, TapWrapper tapDst,
                                            MAC dlSrc, MAC dlDst, IPv4Addr ipSrc, IPv4Addr ipDst,
                                            short tpSrc, short tpDst, byte[] payload) {
        byte[] pkt = PacketHelper.makeUDPPacket(
            dlSrc, ipSrc, dlDst, ipDst, tpSrc, tpDst, payload);
        assertThat("The packet should have been sent from the source tap.",
                   tapSrc.send(pkt));
        assertThat("The packet should not have arrived at the destination tap.",
                   tapDst.recv(), nullValue());
    }

    public static void arpAndCheckReply(
            TapWrapper tap, MAC srcMac, IPv4Addr srcIp,
            IPv4Addr dstIp, MAC expectedMac)
        throws MalformedPacketException {
        assertThat("The ARP request was sent properly",
            tap.send(PacketHelper.makeArpRequest(srcMac, srcIp, dstIp)));
        MAC m = PacketHelper.checkArpReply(tap.recv(), dstIp, srcMac, srcIp);
        assertThat("The resolved MAC is what we expected.",
            m, equalTo(expectedMac));
    }

    /**
     * Sends the ARP request, listens for a reply on the source port, and if
     * the reply is broadcast to any of the other taps will ensure that it's
     * the same reply and drain it from the tap.
     *
     * This is useful to solve a race condition that appears because the mac
     * learning table is not immediately updated locally but needs to write to
     * ZK and wait for the callback before the MAC-port assoc. is effectively
     * learnt. ARP replies might get there before the callback, and trigger a
     * broadcast to all ports.
     *
     * @param tap
     * @param srcMac
     * @param srcIp
     * @param dstIp
     * @param expectedMac
     * @param taps
     * @throws MalformedPacketException
     */
    public static void arpAndCheckReplyDrainBroadcasts(
            TapWrapper tap, MAC srcMac, IPv4Addr srcIp,
            IPv4Addr dstIp, MAC expectedMac, TapWrapper[] taps)
            throws MalformedPacketException {

        arpAndCheckReply(tap, srcMac, srcIp, dstIp, expectedMac);
        if (null == taps)
            return;
        for(TapWrapper otherTap : taps) {
            byte[] bytes = otherTap.recv(200);
            if (bytes != null) {
                MAC m = PacketHelper.checkArpReply(bytes, dstIp, srcMac,
                    srcIp);
                assertThat("Unexpected MAC reply at tap " + otherTap,
                           m, equalTo(expectedMac));
            }
        }
    }

    public static byte[] makeLLDP(MAC dlSrc, MAC dlDst) {
        LLDP packet = new LLDP();
        LLDPTLV chassis = new LLDPTLV();
        // Careful following specced mandatory field types or they'll get
        // interpreted as optional in deserialization and break things
        chassis.setType(LLDPTLV.TYPE_CHASSIS_ID);
        chassis.setLength((short)7);
        chassis.setValue("chassis".getBytes());
        LLDPTLV port = new LLDPTLV();
        port.setType(LLDPTLV.TYPE_PORT_ID);
        port.setLength((short)4);
        port.setValue("port".getBytes());
        LLDPTLV ttl = new LLDPTLV();
        ttl.setType(LLDPTLV.TYPE_TTL);
        ttl.setLength((short) 3);
        ttl.setValue("ttl".getBytes());
        packet.setChassisId(chassis);
        packet.setPortId(port);
        packet.setTtl(ttl);

        Ethernet frame = new Ethernet();
        frame.setPayload(packet);
        frame.setEtherType(LLDP.ETHERTYPE);
        frame.setDestinationMACAddress(dlDst);
        frame.setSourceMACAddress(dlSrc);
        return frame.serialize();
    }

    public static void lldpFromTapArrivesAtTap(
        TapWrapper tapSrc, TapWrapper tapDst, MAC dlSrc, MAC dlDst) {
        byte[] pkt = makeLLDP(dlSrc, dlDst);
        assertThat("The packet should have been sent from the source tap.",
            tapSrc.send(pkt));
        log.info("LLDP sent from {} expecting at {}", dlSrc, dlDst);
        assertThat("The packet should have arrived at the destination tap.",
            tapDst.recv(), allOf(notNullValue(), equalTo(pkt)));
    }

    public static void lldpFromTapDoesntArriveAtTap(
        TapWrapper tapSrc, TapWrapper tapDst, MAC dlSrc, MAC dlDst) {
        byte[] pkt = makeLLDP(dlSrc, dlDst);
        assertThat("The packet should have been sent from the source tap.",
            tapSrc.send(pkt));
        assertThat("The packet should not have arrived at the destination tap.",
            tapDst.recv(), nullValue());
    }

    public static BridgePort[] buildBridgePorts(
        Bridge bridge, boolean exterior, int n) {

        BridgePort[] bridgePorts = new BridgePort[n];
        for (int i = 0; i < n; i++) {
            bridgePorts[i] = (exterior) ?
                bridge.addPort().create() :
                bridge.addPort().create();
        }
        log.info("Created {} ports in bridge {}", bridgePorts.length, bridge);
        return bridgePorts;
    }

    /**
     * This convenient method takes a host, a set of ports, and takes care to
     * bind the ports to newly created taps and confirm that the corresponding
     * LocalPortActive events are triggered. It will return the Taps.
     *
     * @param host
     * @param ports expected exterior, and created
     * @param tapNamePrefix used to name the taps prefix + index,
     *                      if null it'll use a default "testTap"
     * @param probe for LocalPortActive events on the eventStream
     * @return the taps, each will be bound to the port at the same index
     */
    public static TapWrapper[] bindTapsToBridgePorts(
        Host host,
        BridgePort[] ports,
        String tapNamePrefix,
        TestProbe probe) {

        // Make taps
        if (tapNamePrefix == null) {
            tapNamePrefix = "testTap";
        }
        TapWrapper[] taps = new TapWrapper[ports.length];
        for (int i = 0; i < taps.length; i++) {
            log.debug("New tap: {}" , tapNamePrefix + i);
            taps[i] = new TapWrapper(tapNamePrefix + i);
        }

        // Bind to taps
        int i = 0;
        for (BridgePort port : ports) {
            TapWrapper tap = taps[i++];
            log.debug("Bind tap {} to port {}", tap.getName(), port.getId());
            assertEquals(port.getType(), PortType.BRIDGE);
            host.addHostInterfacePort()
                .interfaceName(tap.getName())
                .portId(port.getId()).create();
        }

        // Wait until they become active
        log.info("Waiting for {} LocalPortActive notifications", ports.length);
        Set<UUID> activatedPorts = new HashSet<UUID>();
        for (i = 0; i < ports.length; i++) {
            LocalPortActive activeMsg = probe.expectMsgClass(
                Duration.create(10, TimeUnit.SECONDS),
                LocalPortActive.class);
            log.info("Received a LocalPortActive message about {}",
                     activeMsg.portID());
            assertTrue("The port should be active.", activeMsg.active());
            activatedPorts.add(activeMsg.portID());

        }

        assertThat("The " + ports.length + " exterior ports should be active",
                   activatedPorts, hasSize(ports.length));

        return taps;

    }


    private static String makeZkIptablesCommand(String op, int zkPort) {
        return String.format("sudo iptables -%s INPUT -p tcp --dport %d -j DROP", op, zkPort);
    }

    public static int blockZkCommunications(int zkPort) {
        return ProcessHelper.newProcess(makeZkIptablesCommand("I", zkPort)).
                logOutput(log, "blockCommunications").
                runAndWait();
    }

    public static int unblockZkCommunications(int zkPort) {
        return ProcessHelper.newProcess(makeZkIptablesCommand("D", zkPort)).
                logOutput(log, "unblockCommunications").
                runAndWait();
    }
}
