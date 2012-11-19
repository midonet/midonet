/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midonet.functional_test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


import static java.lang.String.format;

import com.google.inject.*;
import com.midokura.midolman.config.MidolmanConfig;
import com.midokura.midolman.guice.MidolmanModule;
import com.midokura.midolman.guice.config.ConfigProviderModule;
import com.midokura.midonet.functional_test.mocks.MockMgmtStarter;
import com.midokura.midonet.functional_test.utils.*;

import org.apache.commons.io.FileUtils;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;


import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.topology.MaterializedRouterPort;
import com.midokura.midonet.functional_test.topology.Port;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.vm.VMController;
import com.midokura.packets.Ethernet;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.LLDP;
import com.midokura.packets.LLDPTLV;
import com.midokura.packets.MAC;
import com.midokura.packets.MalformedPacketException;
import com.midokura.util.SystemHelper;
import com.midokura.util.process.ProcessHelper;

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
                    String.format("%s -server 127.0.0.1:%d rmr /smoketest",
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
                        String.format(
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

    protected void removeMidoPort(Port port) {
        if (port != null) {
            port.delete();
        }
    }

    public static void removeTenant(Tenant tenant) {
        if (null != tenant)
            tenant.delete();
    }



    public static void stopMidolmanMgmt(MockMgmtStarter mgmt) {
        if (null != mgmt)
            mgmt.stop();
    }

    public static void removeVpn(MidolmanMgmt mgmt, MaterializedRouterPort vpn1) {
        if (mgmt != null && vpn1 != null) {
            mgmt.deleteVpn(vpn1.getVpn());
        }
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

    public static void icmpFromTapArrivesAtTap(
        TapWrapper tapSrc, TapWrapper tapDst,
        MAC dlSrc, MAC dlDst, IntIPv4 ipSrc, IntIPv4 ipDst) {
        byte[] pkt = PacketHelper.makeIcmpEchoRequest(
            dlSrc, ipSrc, dlDst, ipDst);
        assertThat("The packet should have been sent from the source tap.",
            tapSrc.send(pkt));
        assertThat("The packet should have arrived at the destination tap.",
            tapDst.recv(), allOf(notNullValue(), equalTo(pkt)));
    }

    public static void icmpFromTapDoesntArriveAtTap(
        TapWrapper tapSrc, TapWrapper tapDst,
        MAC dlSrc, MAC dlDst, IntIPv4 ipSrc, IntIPv4 ipDst) {
        byte[] pkt = PacketHelper.makeIcmpEchoRequest(
            dlSrc, ipSrc, dlDst, ipDst);
        assertThat("The packet should have been sent from the source tap.",
            tapSrc.send(pkt));
        assertThat("The packet should not have arrived at the destination tap.",
            tapDst.recv(), nullValue());
    }

    public static void arpAndCheckReply(
            TapWrapper tap, MAC srcMac, IntIPv4 srcIp,
            IntIPv4 dstIp, MAC expectedMac)
        throws MalformedPacketException {
        assertThat("The ARP request was sent properly",
            tap.send(PacketHelper.makeArpRequest(srcMac, srcIp, dstIp)));
        MAC m = PacketHelper.checkArpReply(tap.recv(), dstIp, srcMac, srcIp);
        assertThat("The resolved MAC is what we expected.",
            m, equalTo(expectedMac));
    }

    public static byte[] makeLLDP(MAC dlSrc, MAC dlDst) {
        LLDP packet = new LLDP();
        LLDPTLV chassis = new LLDPTLV();
        chassis.setType((byte)0xca);
        chassis.setLength((short)7);
        chassis.setValue("chassis".getBytes());
        LLDPTLV port = new LLDPTLV();
        port.setType((byte) 0);
        port.setLength((short)4);
        port.setValue("port".getBytes());
        LLDPTLV ttl = new LLDPTLV();
        ttl.setType((byte) 40);
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

}
