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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import com.google.common.base.Service;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import com.midokura.midolman.guice.MidolmanActorsModule;
import com.midokura.midolman.guice.MidolmanModule;
import com.midokura.midolman.guice.config.ConfigProviderModule;
import com.midokura.midolman.guice.datapath.DatapathModule;
import com.midokura.midolman.guice.reactor.ReactorModule;
import com.midokura.midolman.guice.zookeeper.ZookeeperConnectionModule;
import com.midokura.midolman.host.guice.HostAgentModule;
import com.midokura.midolman.monitoring.MonitoringAgent;
import com.midokura.midolman.openflow.Controller;
import com.midokura.midolman.openflow.ControllerStubImpl;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.portservice.BgpPortService;
import com.midokura.midolman.portservice.NullPortService;
import com.midokura.midolman.portservice.OpenVpnPortService;
import com.midokura.midolman.portservice.PortService;
import com.midokura.midolman.services.MidolmanActorsService;
import com.midokura.midolman.services.MidolmanService;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.util.Cache;
import com.midokura.midolman.vrn.VRNController;
import com.midokura.midostore.module.MidoStoreModule;
import com.midokura.packets.IntIPv4;
import com.midokura.remote.RemoteHost;
import com.midokura.util.eventloop.SelectListener;
import com.midokura.util.eventloop.SelectLoop;

public class Midolman implements SelectListener {

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

    private MonitoringAgent monitoringAgent;

    private SelectLoop loop;

    private Directory midonetDirectory;

    private Injector injector;

    private Midolman() {
    }

    private void run(String[] args) throws Exception {
        // log git commit info
        Properties properties = new Properties();
        properties.load(
            getClass().getClassLoader().getResourceAsStream("git.properties"));
        log.info("main start -------------------------");
        log.info("branch: {}", properties.get("git.branch"));
        log.info("commit.time: {}", properties.get("git.commit.time"));
        log.info("commit.id: {}", properties.get("git.commit.id"));
        log.info("commit.user: {}", properties.get("git.commit.user.name"));
        log.info("build.time: {}", properties.get("git.build.time"));
        log.info("build.user: {}", properties.get("git.build.user.name"));
        log.info("-------------------------------------");

        log.info("Added SIGTERM handling for cleanup");
        Signal.handle(new Signal("TERM"), new SignalHandler() {
            @Override
            public void handle(Signal sig) {
                doServicesCleanup();
            }
        });

        log.info("Adding shutdownHook");
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                doServicesCleanup();
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

        redirectStdOutAndErrIfRequested(cl);

        String configFilePath = cl.getOptionValue('c', "./conf/midolman.conf");

        injector = Guice.createInjector(
            new ZookeeperConnectionModule(),
            new ReactorModule(),
            new HostAgentModule(),
            new ConfigProviderModule(configFilePath),
            new DatapathModule(),
            new MidoStoreModule(),
            new MidolmanActorsModule(),
            new MidolmanModule()
        );

        // start the services
        injector.getInstance(MidolmanService.class).startAndWait();

        // fire the initialize message to an actor
        injector.getInstance(MidolmanActorsService.class).initProcessing();

        log.info("{} was initialized", MidolmanActorsService.class);

//        basePath = config.getMidolmanRootKey();
//        localNwAddr = IntIPv4.fromString(config.getOpenFlowPublicIpAddress());
//        externalIdKey = config.getOpenvSwitchMidolmanExternalIdKey();
//        vrnId = UUID.fromString(config.getVrnRouterNetworkId());
//        useNxm = config.getOpenFlowUseNxm();
//        enableBgp = config.getMidolmanEnableBgp();
//        disconnected_ttl_seconds = config.getMidolmanDisconnectedTtlSeconds();
//
//        dhcpMtu = config.getMidolmanDhcpMtu();
//
//        executor = Executors.newScheduledThreadPool(1);
//        loop = new SelectLoop(executor);


        // open the OVSDB connection
//        ovsdb = new OpenvSwitchDatabaseConnectionImpl(
//            "Open_vSwitch",
//            config.getOpenvSwitchIpAddr(),
//            config.getOpenvSwitchTcpPort());

//        zkConnection = new ZkConnection(
//            config.getZooKeeperHosts(),
//            config.getZooKeeperSessionTimeout(), this, loop);
//
//        log.debug("about to ZkConnection.open()");
//        zkConnection.open();
//        log.debug("done with ZkConnection.open()");

//        midonetDirectory = zkConnection.getRootDirectory();


//        ref.tell(new SetPortLocal(UUID.randomUUID(), true));

/*
        if (config.getMidolmanStartHostAgent()) {
            nodeAgent = NodeAgent.bootstrapAgent(configProvider,
                                                 zkConnection, ovsdb);
            nodeAgent.start();
        } else {
            log.info(
                "Not starting node agent because it was not enabled in the " +
                    "configuration file.");
        }

*/
/*
        if (config.getMidolmanEnableMonitoring()) {
            log.info("Starting monitoring...");
            NodeAgentHostIdProvider hostIdProvider =
                new NodeAgentHostIdProvider(nodeAgent);
            monitoringAgent =
                MonitoringAgent.bootstrapMonitoring(configProvider,
                                                    hostIdProvider);
        }
*/

//        listenSock = ServerSocketChannel.open();
//        listenSock.configureBlocking(false);
//        listenSock.socket().bind(
//            new java.net.InetSocketAddress(
//                config.getOpenFlowControllerPort()));
//
//        loop.register(listenSock, SelectionKey.OP_ACCEPT, this);

//        log.debug("before doLoop which will block");
//        loop.doLoop();
//        log.debug("after doLoop is done");
//
//        if (nodeAgent != null) {
//            log.debug("Stopping the node agent");
//            nodeAgent.stop();
//            log.debug("Node agent stopped");
//        }

//        vrnCache = CacheFactory.create(config);

        log.info("main finish");
    }

    private void doServicesCleanup() {
        if ( injector == null )
            return;

        MidolmanService instance =
            injector.getInstance(MidolmanService.class);

        if (instance.state() == Service.State.TERMINATED)
            return;

        try {
            instance.stopAndWait();
        } catch (Exception e) {
            log.error("Exception ", e);
        } finally {
            log.info("Exiting. BYE (signal)!");
        }
    }

    private void redirectStdOutAndErrIfRequested(CommandLine commandLine) {

        String targetStdOut = commandLine.getOptionValue("redirectStdOut");
        if (targetStdOut != null) {
            try {
                File targetStdOutFile = new File(targetStdOut);
                if (targetStdOutFile.isFile() && targetStdOutFile.canWrite()) {
                    PrintStream newStdOutStr = new PrintStream(
                        targetStdOutFile);
                    newStdOutStr.println("[Begin redirected output]");

                    System.setOut(newStdOutStr);
                }
            } catch (FileNotFoundException e) {
                log.error("Could not redirect stdout to {}", targetStdOut, e);
            }
        }

        String targetStdErr = commandLine.getOptionValue("redirectStdErr");

        if (targetStdErr != null) {
            try {
                File targetStdErrFile = new File(targetStdErr);
                if (targetStdErrFile.isFile() && targetStdErrFile.canWrite()) {
                    PrintStream newStdErrStr = new PrintStream(
                        targetStdErrFile);
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
                                                                     ovsdb,
                                                                     midonetDirectory,
                                                                     basePath);
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
        } catch (Throwable e) {
            log.warn("handleEvent resulted in Throwable!", e);
        }
    }

    public static void main(String[] args) {
        try {
            RemoteHost.getSpecification();

            new Midolman().run(args);
//            new Midolman().runTest();
        } catch (Exception e) {
            log.error("main caught", e);
            System.exit(-1);
        }
    }

    private void runTest() {
    }
}
