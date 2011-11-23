package com.midokura.midolman;

import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.layer3.Router;
import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.openvswitch.BridgeBuilder;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.openvswitch.PortBuilder;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.TCP;
import com.midokura.midolman.packets.UDP;
import com.midokura.midolman.rules.Condition;
import com.midokura.midolman.rules.ForwardNatRule;
import com.midokura.midolman.rules.JumpRule;
import com.midokura.midolman.rules.LiteralRule;
import com.midokura.midolman.rules.NatTarget;
import com.midokura.midolman.rules.ReverseNatRule;
import com.midokura.midolman.rules.Rule;
import com.midokura.midolman.rules.RuleResult;
import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkPathManager;

public class Setup implements Watcher {

    static final Logger log = LoggerFactory.getLogger(Setup.class);

    private static final String ZK_CREATE = "zk_create";
    private static final String ZK_DESTROY = "zk_destroy";
    private static final String ZK_SETUP = "zk_setup";
    private static final String ZK_TEARDOWN = "zk_teardown";
    private static final String ZK_ADD_RULES = "zk_add_rules";
    private static final String ZK_DEL_RULES = "zk_del_rules";
    private static final String OVS_SETUP = "zk_setup";
    private static final String OVS_TEARDOWN = "zk_teardown";
    private static final String MIDONET_QDISC_CREATE = "midonet_qdisc_create";
    private static final String NOVA_QDISC_CREATE = "nova_qdisc_create";
    private static final String QDISC_DESTROY = "qdisc_destroy";

    private int disconnected_ttl_seconds;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> disconnected_kill_timer = null;
    private ZkConnection zkConnection;
    private Directory rootDir;
    private HierarchicalConfiguration config;
    private OpenvSwitchDatabaseConnection ovsdb;
    private String zkBasePath;
    BridgeZkManager bridgeMgr;
    ChainZkManager chainMgr;
    PortZkManager portMgr;
    RouterZkManager routerMgr;
    RouteZkManager routeMgr;
    RuleZkManager ruleMgr;

    private void run(String[] args) throws Exception {
        Options options = new Options();
        options.addOption("c", "configFile", true, "config file path");
        CommandLineParser parser = new GnuParser();
        CommandLine cl = parser.parse(options, args);
        String configFilePath = cl.getOptionValue('c', "./conf/midolman.conf");

        config = new HierarchicalINIConfiguration(configFilePath);
        executor = Executors.newScheduledThreadPool(1);

        args = cl.getArgs();
        if (args.length == 0)
            return;
        String command = args[0].toLowerCase();
        if (command.equals(ZK_CREATE))
            zkCreate();
        else if (command.equals(ZK_DESTROY))
            zkDestroy();
        else if (command.equals(ZK_SETUP))
            zkSetup();
        else if (command.equals(ZK_ADD_RULES))
            zkAddRules(args[1]);
        else if (command.equals(ZK_DEL_RULES))
            zkDelRules(args[1]);
        else if (command.equals(ZK_TEARDOWN))
            zkTearDown();
        else if (command.equals(OVS_SETUP))
            ovsSetup();
        else if (command.equals(OVS_TEARDOWN))
            ovsTearDown();
        else if (command.equals(MIDONET_QDISC_CREATE))
            setupTrafficPriorityQdiscsMidonet();
        else if (command.equals(NOVA_QDISC_CREATE))
            setupTrafficPriorityQdiscsNova();
        else if (command.equals(QDISC_DESTROY))
            removeTrafficPriorityQdiscs();
        else
            System.out.println("Unrecognized command. Exiting.");
    }

    private void zkDelRules(String routerIdStr) throws Exception {
        initZK();
        UUID routerId = UUID.fromString(routerIdStr);
        Iterator<ZkNodeEntry<UUID, ChainConfig>> iter = chainMgr.list(routerId)
                .iterator();
        while (iter.hasNext()) {
            chainMgr.delete(iter.next().key);
        }
    }

    private void zkAddRules(String routerIdStr) throws Exception {
        initZK();
        UUID routerId = UUID.fromString(routerIdStr);
        UUID preChainId = chainMgr.create(new ChainConfig(Router.PRE_ROUTING,
                routerId));
        String srcFilterChainName = "filterSrcByPortId";
        UUID srcFilterChainId = chainMgr.create(new ChainConfig(
                srcFilterChainName, routerId));
        UUID postChainId = chainMgr.create(new ChainConfig(Router.POST_ROUTING,
                routerId));
        // First, create a chain that will be jumped to from the pre-routing
        // chain. This chain makes sure that packets arriving on a port have
        // nwSrc addresses in that port's localNwAddr.
        Condition cond;
        int position = 0;
        Rule r;
        Iterator<ZkNodeEntry<UUID, PortConfig>> iter = portMgr.listRouterPorts(
                routerId).iterator();
        while (iter.hasNext()) {
            ZkNodeEntry<UUID, PortConfig> entry = iter.next();
            PortDirectory.MaterializedRouterPortConfig config;
            config = PortDirectory.MaterializedRouterPortConfig.class.cast(entry.value);
            // A down-port can only receive packets from network addresses that
            // are 'local' to the port.
            cond = new Condition();
            cond.inPortIds = new HashSet<UUID>();
            cond.inPortIds.add(entry.key);
            cond.nwSrcIp = config.localNwAddr;
            cond.nwSrcLength = (byte) config.localNwLength;
            r = new LiteralRule(cond, RuleResult.Action.RETURN,
                    srcFilterChainId, position);
            position++;
            ruleMgr.create(r);
        }
        // Finally, anything that gets to the end of this chain is dropped.
        r = new LiteralRule(new Condition(), RuleResult.Action.DROP,
                srcFilterChainId, position);
        position++;
        ruleMgr.create(r);

        // Now create the pre-routing chain. This chain first jumps to the
        // srcFilterChain, then DNATs (tcp) address 180.0.0.4:80 to 10.0.0.20.
        position = 0;
        // The pre-routing chain needs a jump rule to the localAddressesChain
        r = new JumpRule(new Condition(), srcFilterChainName, preChainId,
                position);
        position++;
        ruleMgr.create(r);
        int publicDnatAddr = 0xb4000004;
        int internDnatAddr = 0x0a000014;
        short natPublicTpPort = 80;
        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(internDnatAddr, internDnatAddr, natPublicTpPort,
                natPublicTpPort));
        cond = new Condition();
        cond.nwProto = TCP.PROTOCOL_NUMBER;
        cond.nwDstIp = publicDnatAddr;
        cond.nwDstLength = 32;
        cond.tpDstStart = natPublicTpPort;
        cond.tpDstEnd = natPublicTpPort;
        r = new ForwardNatRule(cond, RuleResult.Action.ACCEPT, preChainId,
                position, true /* dnat */, nats);
        position++;
        ruleMgr.create(r);

        // Now create the post-routing chain. This reverses the dnat and
        // quarantines certain hosts.
        position = 0;
        cond = new Condition();
        cond.nwProto = TCP.PROTOCOL_NUMBER;
        cond.nwSrcIp = internDnatAddr;
        cond.nwSrcLength = 32;
        cond.tpSrcStart = natPublicTpPort;
        cond.tpSrcEnd = natPublicTpPort;
        r = new ReverseNatRule(cond, RuleResult.Action.CONTINUE, postChainId,
                position, true /* dnat */);
        position++;
        ruleMgr.create(r);
        // Now add rules to quarantine hosts 10.0.0.10 and 10.0.1.10.
        cond = new Condition();
        cond.nwDstIp = 0x0a00000a;
        cond.nwDstLength = 32;
        r = new LiteralRule(cond, RuleResult.Action.REJECT, postChainId,
                position);
        position++;
        ruleMgr.create(r);
        cond = new Condition();
        cond.nwDstIp = 0x0a00010a;
        cond.nwDstLength = 32;
        r = new LiteralRule(cond, RuleResult.Action.REJECT, postChainId,
                position);
        position++;
        ruleMgr.create(r);
    }

    private void zkCreate() throws Exception {
        initZK();
        String[] parts = zkBasePath.split("/");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.equals(""))
                continue;
            sb.append("/").append(p);
            zkBasePath = sb.toString();
            try {
                rootDir.add(zkBasePath, null, CreateMode.PERSISTENT);
            } catch (NodeExistsException e) {
            }
        }
        // Create all the top level directories
        Setup.createZkDirectoryStructure(rootDir, zkBasePath);
    }

    /**
     * Destroy the base path and everything underneath.
     *
     * @throws Exception
     */
    private void zkDestroy() throws Exception {
        initZK();
        destroyZkDirectoryContents(zkBasePath);
        rootDir.delete(zkBasePath);
    }

    private void destroyZkDirectoryContents(String path)
            throws KeeperException, InterruptedException {
        Set<String> children = rootDir.getChildren(path, null);
        for (String child : children) {
            String childPath = path + "/" + child;
            destroyZkDirectoryContents(childPath);
            rootDir.delete(childPath);
        }
    }

    private void zkSetup() throws Exception {
        initZK();

        // First, create a simple bridge with 3 ports.
        UUID deviceId = bridgeMgr.create(new BridgeConfig());
        log.info("Created a bridge with id {}", deviceId.toString());
        UUID portId;
        PortConfig portConfig;
        for (int i = 0; i < 3; i++) {
            portConfig = new PortDirectory.BridgePortConfig(deviceId);
            portId = portMgr.create(portConfig);
            log.info("Created a bridge port with id {}", portId.toString());
        }
        // Now create a router with 3 ports.
        deviceId = routerMgr.create();
        log.info("Created a router with id {}", deviceId.toString());
        // Add two ports to the router. Port-j should route to subnet
        // 10.0.<j>.0/24.
        int routerNw = 0x0a000000;
        for (int j = 0; j < 3; j++) {
            int portNw = routerNw + (j << 8);
            int portAddr = portNw + 1;
            portConfig = new PortDirectory.MaterializedRouterPortConfig(
                    deviceId, portNw, 24, portAddr, null, portNw, 24, null);
            portId = portMgr.create(portConfig);
            log.info("Created a router port with id {} that routes to {}",
                    portId.toString(), IPv4.fromIPv4Address(portNw));

            Route rt = new Route(0, 0, portNw, 24, NextHop.PORT, portId, 0, 10,
                    null, deviceId);
            routeMgr.create(rt);
        }
    }

    /**
     * Remove everything from Midonet's top-level paths but leave those
     * directories.
     *
     * @throws Exception
     */
    private void zkTearDown() throws Exception {
        initZK();
        Set<String> paths = getTopLevelPaths(zkBasePath);
        for (String path : paths) {
            destroyZkDirectoryContents(path);
        }
    }

    private void ovsSetup() {
        initOVS();
        /*
         * String externalIdKey =
         * config.configurationAt("openvswitch").getString(
         * "midolman_ext_id_key", "midolman-vnet"); String dpName =
         * "mido_bridge1"; BridgeBuilder ovsBridgeBuilder =
         * ovsdb.addBridge(dpName); ovsBridgeBuilder.externalId(externalIdKey,
         * deviceId.toString()); ovsBridgeBuilder.build(); PortBuilder
         * ovsPortBuilder = ovsdb.addTapPort(dpName, "mido_br_port" + i);
         * ovsPortBuilder.externalId(externalIdKey, portId.toString());
         * ovsPortBuilder.build();
         */

    }

    private void ovsTearDown() {
        initOVS();
    }

    private void initZK() throws Exception {
        String zkHosts = config.configurationAt("zookeeper")
                               .getString("zookeeper_hosts", "127.0.0.1:2181");
        zkConnection = new ZkConnection(zkHosts,
                config.configurationAt("zookeeper")
                      .getInt("session_timeout", 30000),
                this);

        log.debug("about to ZkConnection.open()");
        zkConnection.open();
        log.debug("done with ZkConnection.open()");

        rootDir = zkConnection.getRootDirectory();
        zkBasePath = config.configurationAt("midolman").getString(
                "midolman_root_key");

        bridgeMgr = new BridgeZkManager(rootDir, zkBasePath);
        chainMgr = new ChainZkManager(rootDir, zkBasePath);
        portMgr = new PortZkManager(rootDir, zkBasePath);
        routerMgr = new RouterZkManager(rootDir, zkBasePath);
        routeMgr = new RouteZkManager(rootDir, zkBasePath);
        ruleMgr = new RuleZkManager(rootDir, zkBasePath);
    }

    private void initOVS() {
        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch", config
                .configurationAt("openvswitch").getString(
                        "openvswitchdb_ip_addr", "127.0.0.1"), config
                .configurationAt("openvswitch").getInt(
                        "openvswitchdb_tcp_port", 6634));
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

    private static Set<String> getTopLevelPaths(String basePath) {
        ZkPathManager pathMgr = new ZkPathManager(basePath);
        Set<String> paths = new HashSet<String>();
        paths.add(pathMgr.getBgpPath());
        paths.add(pathMgr.getBridgesPath());
        paths.add(pathMgr.getChainsPath());
        paths.add(pathMgr.getRulesPath());
        paths.add(pathMgr.getGrePath());
        paths.add(pathMgr.getPortsPath());
        paths.add(pathMgr.getRoutersPath());
        paths.add(pathMgr.getRoutesPath());
        paths.add(pathMgr.getVRNPortLocationsPath());
        return paths;
    }

    public static void createZkDirectoryStructure(Directory rootDir,
            String basePath) throws KeeperException, InterruptedException {
        Set<String> paths = Setup.getTopLevelPaths(basePath);
        for (String path : paths) {
            rootDir.add(path, null, CreateMode.PERSISTENT);
        }
    }

    protected void setupTrafficPriorityQdiscsMidonet()
            throws IOException {
        int markValue = 0x00ACCABA;  // Midokura's OUI.
        Runtime rt = Runtime.getRuntime();
        String iface = config.configurationAt("midolman")
                             .getString("control_interface", "eth0");

        // Add a prio qdisc to root, and have marked packets prioritized.
        rt.exec("sudo -n tc qdisc add dev " + iface + " root handle 1: prio");
        rt.exec("sudo -n tc filter add dev " + iface +
                " parent 1: protocol ip prio 1 handle " + markValue +
                " fw flowid 1:1");

        // Add rules to mark ZooKeeper packets.
        String zkHosts = config.configurationAt("zookeeper")
                               .getString("zookeeper_hosts", "127.0.0.1:2181");
        for (String zkServer : zkHosts.split(",")) {
            String[] hostport = zkServer.split(":");
            assert hostport.length == 2;
            setupTrafficPriorityRule(hostport[0], hostport[1]);
        }

        // Add rules to mark memcached packets.
        String mcHosts = config.configurationAt("memcache")
                               .getString("memcache_hosts", "127.0.0.1:11211");
        for (String mcServer : mcHosts.split(",")) {
            String[] hostport = mcServer.split(":");
            setupTrafficPriorityRule(hostport[0], hostport[1]);
        }

        // Add rules to mark Voldemort packets.
        String volHosts = config.configurationAt("voldemort")
                                .getString("servers", "127.0.0.1:6666");
        for (String volUrl : volHosts.split(",")) {
            String[] hostport = volUrl.split(":/");
            // "proto":""/""/"host":"port"
            assert hostport.length == 5;
            setupTrafficPriorityRule(hostport[3], hostport[4]);
        }

    }

    protected void setupTrafficPriorityQdiscsNova()
            throws IOException, ParseException {
        Options options = new Options();
        options.addOption("rabbit_host", true, "");
        options.addOption("sql_connection", true, "");
        options.addOption("vncproxy_url", true, "");
        options.addOption("ec2_url", true, "");
        CommandLineParser parser = new GnuParser();
        FileReader confFile = new FileReader("/etc/nova/nova.conf");
        char[] confBytes = new char[5000];
        int confByteLength = confFile.read(confBytes);
        String[] allArgs = (new String(confBytes, 0, confByteLength)).split("\n");
        CommandLine cl = parser.parse(options, allArgs);

        // RabbitMQ
        String rabbitHost = cl.getOptionValue("rabbit_host", "127.0.0.1");
        setupTrafficPriorityRule(rabbitHost, "5672");

        // mysql
        URL mysqlUrl = new URL(
            cl.getOptionValue("sql_connection",
                              "mysql://root:midokura@127.0.0.1/nova_trunk"));
        int port = mysqlUrl.getPort();
        if (port == -1)
            port = 3306;
        setupTrafficPriorityRule(mysqlUrl.getHost(), Integer.toString(port));

        // VNC
        URL vncUrl = new URL(
            cl.getOptionValue("vncproxy_url", "http://127.0.0.1:6080"));
        port = vncUrl.getPort();
        if (port == -1)
            port = 6080;
        setupTrafficPriorityRule(vncUrl.getHost(), Integer.toString(port));

        // EC2
        URL ec2Url = new URL(
            cl.getOptionValue("ec2_url", "http://127.0.0.1:8773/services/Cloud"));
        port = ec2Url.getPort();
        if (port == -1)
            port = 8773;
        setupTrafficPriorityRule(ec2Url.getHost(), Integer.toString(port));
    }

    protected void removeTrafficPriorityQdiscs()
            throws IOException {
        // Clear existing qdiscs
        String iface = config.configurationAt("midolman")
                             .getString("control_interface", "eth0");
        Runtime.getRuntime().exec("sudo -n tc qdisc del dev " + iface + " root");
    }

    protected static void setupTrafficPriorityRule(String host, String port)
            throws IOException {
        int markValue = 0x00ACCABA;  // Midokura's OUI.
        Runtime.getRuntime().exec(
                "sudo -n iptables -t mangle -A POSTROUTING -p tcp -m tcp -d " +
                host + " --dport " + port + " -j MARK --set-mark " + markValue);
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
