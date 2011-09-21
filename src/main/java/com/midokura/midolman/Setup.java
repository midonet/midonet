package com.midokura.midolman;

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
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.openvswitch.BridgeBuilder;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.openvswitch.PortBuilder;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.PortDirectory.*;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkPathManager;

public class Setup implements Watcher {

    static final Logger log = LoggerFactory.getLogger(Setup.class);

    private int disconnected_ttl_seconds;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> disconnected_kill_timer = null;
    private ZkConnection zkConnection;
    private Directory dir;

    private void run(String[] args) throws Exception {
        Options options = new Options();
        options.addOption("c", "configFile", true, "config file path");
        CommandLineParser parser = new GnuParser();
        CommandLine cl = parser.parse(options, args);
        String configFilePath = cl.getOptionValue('c', "./conf/midolman.conf");

        HierarchicalConfiguration config = new HierarchicalINIConfiguration(
                configFilePath);
        Configuration midolmanConfig = config.configurationAt("midolman");

        executor = Executors.newScheduledThreadPool(1);

        OpenvSwitchDatabaseConnection ovsdb = new OpenvSwitchDatabaseConnectionImpl(
                "Open_vSwitch", config.configurationAt("openvswitch")
                        .getString("openvswitchdb_ip_addr", "127.0.0.1"),
                config.configurationAt("openvswitch").getInt(
                        "openvswitchdb_tcp_port", 6634));

        zkConnection = new ZkConnection(config.configurationAt("zookeeper")
                .getString("zookeeper_hosts", "127.0.0.1:2181"), config
                .configurationAt("zookeeper").getInt("session_timeout", 30000),
                this);

        log.debug("about to ZkConnection.open()");
        zkConnection.open();
        log.debug("done with ZkConnection.open()");

        dir = zkConnection.getRootDirectory();
        String midoBasePath = midolmanConfig.getString("midolman_root_key");
        String[] parts = midoBasePath.split("/");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.equals(""))
                continue;
            sb.append("/").append(p);
            midoBasePath = sb.toString();
            dir.add(midoBasePath, null, CreateMode.PERSISTENT);
        }

        // Create all the top level directories
        Setup.createZkDirectoryStructure(dir.getSubDirectory(midoBasePath));

        dir = dir.getSubDirectory(midoBasePath);
        BridgeZkManager bridgeMgr = new BridgeZkManager(dir, "");
        // ChainZkManager chainMgr = new ChainZkManager(dir, "");
        PortZkManager portMgr = new PortZkManager(dir, "");
        RouterZkManager routerMgr = new RouterZkManager(dir, "");
        RouteZkManager routeMgr = new RouteZkManager(dir, "");
        // RuleZkManager ruleMgr = new RuleZkManager(dir, "");

        String externalIdKey = config.configurationAt("openvswitch").getString(
                "midolman_ext_id_key", "midolman-vnet");

        // First, create a simple bridge with 3 ports.
        UUID deviceId = bridgeMgr.create(new BridgeConfig());
        System.out.println(String.format("Created a bridge with id %s",
                deviceId.toString()));
        String dpName = "mido_bridge1";
        BridgeBuilder ovsBridgeBuilder = ovsdb.addBridge(dpName);
        ovsBridgeBuilder.externalId(externalIdKey, deviceId.toString());
        ovsBridgeBuilder.build();
        UUID portId;
        PortBuilder ovsPortBuilder;
        PortConfig portConfig;
        for (int i = 0; i < 3; i++) {
            portConfig = new BridgePortConfig(deviceId);
            portId = portMgr.create(portConfig);
            System.out.println(String.format(
                    "Created a bridge port with id %s", portId.toString()));
            ovsPortBuilder = ovsdb.addTapPort(dpName, "mido_br_port"+i);
            ovsPortBuilder.externalId(externalIdKey, portId.toString());
            ovsPortBuilder.build();
        }
        // Now create a router with 3 ports.
        deviceId = routerMgr.create();
        System.out.println(String.format("Created a router with id %s",
                deviceId.toString()));
        ovsBridgeBuilder = ovsdb.addBridge("mido_router1");
        ovsBridgeBuilder.externalId(externalIdKey, deviceId.toString());
        ovsBridgeBuilder.build();
        // Add two ports to the router. Port-j should route to subnet
        // 10.0.<j>.0/24.
        int routerNw = 0x0a000000;
        for (int j = 0; j < 3; j++) {
            int portNw = routerNw + (j << 8);
            int portAddr = portNw + 1;
            portConfig = new PortDirectory.MaterializedRouterPortConfig(
                    deviceId, portNw, 24, portAddr, null, portNw, 24, null);
            portId = portMgr.create(portConfig);
            System.out.println(String.format("Created a router port with id "
                    + "%s that routes to %s", portId.toString(), IPv4
                    .addrToString(portNw)));
            Route rt = new Route(0, 0, portNw, 24, NextHop.PORT, portId, 0, 10,
                    null, deviceId);
            routeMgr.create(rt);
            ovsPortBuilder = ovsdb.addTapPort(dpName, "mido_rtr_port"+j);
            ovsPortBuilder.externalId(externalIdKey, portId.toString());
            ovsPortBuilder.build();
        }
    }

    @Override
    public synchronized void process(WatchedEvent event) {
        if (event.getState() == Watcher.Event.KeeperState.Disconnected) {
            log.warn("KeeperState is Disconnected, shutdown soon");

            disconnected_kill_timer = executor.schedule(new Runnable() {
                @Override
                public void run() {
                    log.error(
                            "have been disconnected for {} seconds, so exiting",
                            disconnected_ttl_seconds);
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

    public static void createZkDirectoryStructure(Directory dir)
            throws KeeperException, InterruptedException {
        ZkPathManager pathMgr = new ZkPathManager("");
        dir.add(pathMgr.getBgpPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getBridgesPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getChainsPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRulesPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getGrePath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getPortsPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRoutersPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRoutesPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getVRNPortLocationsPath(), null, CreateMode.PERSISTENT);
    }

    public static void main(String[] args) {
        try {
            new Setup().run(args);
        } catch (Exception e) {
            log.error("main caught", e);
            System.exit(-1);
        }
    }

}
