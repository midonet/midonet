/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.NodeAgent;
import com.midokura.midolman.eventloop.SelectListener;
import com.midokura.midolman.eventloop.SelectLoop;
import com.midokura.midolman.monitoring.MonitoringAgent;
import com.midokura.midolman.monitoring.NodeAgentHostIdProvider;
import com.midokura.midolman.openflow.Controller;
import com.midokura.midolman.openflow.ControllerStubImpl;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.portservice.BgpPortService;
import com.midokura.midolman.portservice.NullPortService;
import com.midokura.midolman.portservice.OpenVpnPortService;
import com.midokura.midolman.portservice.PortService;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.CacheFactory;
import com.midokura.midolman.vrn.VRNController;
import com.midokura.remote.RemoteHost;


public class Midolman implements SelectListener, Watcher {

    static final Logger log = LoggerFactory.getLogger(Midolman.class);

    private boolean useNxm;
    private boolean enableBgp;
    private int disconnected_ttl_seconds;
    private IntIPv4 localNwAddr;
    private ScheduledFuture<?> disconnected_kill_timer = null;
    private String basePath;
    private String externalIdKey;
    private UUID vrnId;
    private int dhcpMtu;
    private Cache vrnCache;

    private Controller controller;
    private ScheduledExecutorService executor;
    private OpenvSwitchDatabaseConnection ovsdb;
    private ZkConnection zkConnection;
    private ServerSocketChannel listenSock;

    private NodeAgent nodeAgent;
    private MonitoringAgent monitoringAgent;

    private SelectLoop loop;

    private Directory midonetDirectory;

    private Midolman() {}

    private void run(String[] args) throws Exception {
        // log git commit info
        Properties properties = new Properties();
        properties.load(getClass().getClassLoader().getResourceAsStream("git.properties"));
        log.info("main start -------------------------");
        log.info("branch: {}", properties.get("git.branch"));
        log.info("commit.time: {}", properties.get("git.commit.time"));
        log.info("commit.id: {}", properties.get("git.commit.id"));
        log.info("commit.user: {}", properties.get("git.commit.user.name"));
        log.info("build.time: {}", properties.get("git.build.time"));
        log.info("build.user: {}", properties.get("git.build.user.name"));
        log.info("-------------------------------------");

        log.info("Adding shutdownHook");
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                log.warn("In shutdown hook: disconnecting ZK.");
                if (null != zkConnection)
                    zkConnection.close();
                if (null != nodeAgent)
                    nodeAgent.stop();
                if (null != monitoringAgent)
                    monitoringAgent.stop();
                log.warn("Exiting. BYE!");
            }
        });

        Options options = new Options();
        options.addOption("c", "configFile", true, "config file path");

        // TODO(mtoader): Redirected where?
        options.addOption("redirectStdOut", true,
                          "will cause the stdout to be redirected");
        options.addOption("redirectStdErr", true,
                          "will cause the stderr to be redirected");
        CommandLineParser parser = new GnuParser();
        CommandLine cl = parser.parse(options, args);

        String configFilePath = cl.getOptionValue('c', "./conf/midolman.conf");

        redirectStdOutAndErrIfRequested(cl);

        HierarchicalConfiguration config =
                new HierarchicalINIConfiguration(configFilePath);
        Configuration midolmanConfig = config.configurationAt("midolman");

        basePath = midolmanConfig.getString("midolman_root_key");
        localNwAddr = IntIPv4.fromString(config.configurationAt(
                "openflow").getString("public_ip_address"));
        externalIdKey = config.configurationAt("openvswitch")
                .getString("midolman_ext_id_key", "midolman-vnet");
        vrnId = UUID.fromString(
                config.configurationAt("vrn").getString("router_network_id"));
        useNxm = config.configurationAt("openflow").getBoolean("use_nxm", false);
        enableBgp = midolmanConfig.getBoolean("enable_bgp", true);
        disconnected_ttl_seconds =
            midolmanConfig.getInteger("disconnected_ttl_seconds", 30);
        dhcpMtu = midolmanConfig.getInteger("dhcp_mtu", 1450);

        executor = Executors.newScheduledThreadPool(1);
        loop = new SelectLoop(executor);

        // open the OVSDB connection
        ovsdb = new OpenvSwitchDatabaseConnectionImpl(
                "Open_vSwitch",
                config.configurationAt("openvswitch")
                      .getString("openvswitchdb_ip_addr", "127.0.0.1"),
                config.configurationAt("openvswitch")
                      .getInt("openvswitchdb_tcp_port", 6634));

        zkConnection = new ZkConnection(
                config.configurationAt("zookeeper")
                      .getString("zookeeper_hosts", "127.0.0.1:2181"),
                config.configurationAt("zookeeper")
                      .getInt("session_timeout", 30000), this, loop);

        log.debug("about to ZkConnection.open()");
        zkConnection.open();
        log.debug("done with ZkConnection.open()");

        midonetDirectory = zkConnection.getRootDirectory();

        boolean startNodeAgent = midolmanConfig.getBoolean("start_host_agent",
                                                           false);

        if (startNodeAgent) {
            nodeAgent = NodeAgent.bootstrapAgent(config, zkConnection, ovsdb);
            nodeAgent.start();
        } else {
            log.info("Not starting node agent because it was not enabled in " +
                     "the configuration file.");
        }

        boolean startMonitoring = config.configurationAt("monitoring")
            .getBoolean("start_monitoring", true);

        if (startMonitoring) {
            log.info("Starting monitoring...");
            NodeAgentHostIdProvider hostIdProvider =
                new NodeAgentHostIdProvider(nodeAgent);
            monitoringAgent = MonitoringAgent.bootstrapMonitoring(config,
                                                                  hostIdProvider);
        }

        vrnCache = CacheFactory.create(config);

        listenSock = ServerSocketChannel.open();
        listenSock.configureBlocking(false);
        listenSock.socket().bind(
                new java.net.InetSocketAddress(
                        config.configurationAt("openflow")
                              .getInt("controller_port", 6633)));

        loop.register(listenSock, SelectionKey.OP_ACCEPT, this);

        log.debug("before doLoop which will block");
        loop.doLoop();
        log.debug("after doLoop is done");

        if (nodeAgent != null) {
            log.debug("Stopping the node agent");
            nodeAgent.stop();
            log.debug("Node agent stopped");
        }

        log.info("main finish");
    }

    private void redirectStdOutAndErrIfRequested(CommandLine commandLine) {

        String targetStdOut = commandLine.getOptionValue("redirectStdOut");
        if (targetStdOut != null ) {
            try {
                File targetStdOutFile = new File(targetStdOut);
                if (targetStdOutFile.isFile() && targetStdOutFile.canWrite()) {
                    PrintStream newStdOutStr = new PrintStream(targetStdOutFile);
                    newStdOutStr.println("[Begin redirected output]");

                    System.setOut(newStdOutStr);
                }
            } catch (FileNotFoundException e) {
                log.error("Could not redirect stdout to {}", targetStdOut, e);
            }
        }

        String targetStdErr = commandLine.getOptionValue("redirectStdErr");

        if (targetStdErr != null ) {
            try {
                File targetStdErrFile = new File(targetStdErr);
                if (targetStdErrFile.isFile() && targetStdErrFile.canWrite()) {
                    PrintStream newStdErrStr = new PrintStream(targetStdErrFile);
                    newStdErrStr.println("[Begin redirected output]");

                    System.setErr(newStdErrStr);
                }
            } catch (FileNotFoundException e) {
                log.error("Could not redirect stderr to {}", targetStdErr, e);
            }
        }
    }

    @Override
    public void handleEvent(SelectionKey key) throws IOException {
        log.info("handleEvent " + key);

        SocketChannel sock = listenSock.accept();

        // If the controller is already set, don't accept any new connection.
        if (controller != null) {
            log.warn("Midolman.handleEvent: Controller has already started.");
            // TODO(pino): this is a hack so that the connection request is
            // TODO:       handled and removed from the select events.
            sock.close();
            return;
        }

        try {
            log.info("handleEvent accepted connection from " +
                     sock.socket().getRemoteSocketAddress());

            sock.socket().setTcpNoDelay(true);
            sock.configureBlocking(false);

            // Create a Quagga BGP port service.
            PortService bgpPortService = null;
            if (enableBgp) {
                bgpPortService = BgpPortService.createBgpPortService(loop,
                        ovsdb, midonetDirectory, basePath);
            } else {
                log.info("BGP disabled by configuration.");
                bgpPortService = new NullPortService();
            }

            // Create an OpenVPN VPN port service.
            PortService vpnPortService =
                OpenVpnPortService.createVpnPortService(
                    ovsdb, externalIdKey, midonetDirectory, basePath);

            VRNController vrnController =
                new VRNController(midonetDirectory, basePath,
                                  localNwAddr, ovsdb, loop, vrnCache,
                                  externalIdKey, vrnId, useNxm,
                                  bgpPortService, vpnPortService, dhcpMtu);

            if (monitoringAgent != null) {
                vrnController.addControllerObserver(
                    monitoringAgent.createVRNObserver());
            }

            controller = vrnController;

            ControllerStubImpl controllerStubImpl =
                new ControllerStubImpl(sock, loop, controller);

            SelectionKey switchKey =
                loop.register(sock, SelectionKey.OP_READ, controllerStubImpl);

            loop.wakeup();

            controllerStubImpl.start();
        } catch (Exception e) {
            log.warn("handleEvent", e);
        }
    }

    @Override
    public synchronized void process(WatchedEvent event) {
        if (event.getState() == Watcher.Event.KeeperState.Disconnected) {
            log.warn("KeeperState is Disconnected, shutdown soon");

            disconnected_kill_timer = executor.schedule(new Runnable() {
                @Override
                public void run() {
                    log.error("have been disconnected for {} seconds, " +
                              "so exiting", disconnected_ttl_seconds);
                    System.exit(-1);
                }
            }, disconnected_ttl_seconds, TimeUnit.SECONDS);
        }

        if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
            log.info("KeeperState is SyncConnected");

            if (disconnected_kill_timer != null) {
                log.info("canceling shutdown");
                disconnected_kill_timer.cancel(true);
                disconnected_kill_timer = null;
            }
        }

        if (event.getState() == Watcher.Event.KeeperState.Expired) {
            log.warn("KeeperState is Expired, shutdown now");
            System.exit(-1);
        }
    }

    public static void main(String[] args) {
        try {
            RemoteHost.getSpecification();

            new Midolman().run(args);
        } catch (Exception e) {
            log.error("main caught", e);
            System.exit(-1);
        }
    }

}
