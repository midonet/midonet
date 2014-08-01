/*
 * Copyright 2012 Midokura Inc.
 * Copyright 2013 Midokura PTE LTD.
 */

package org.midonet.midolman;

import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.util.Sudo;
import org.midonet.midolman.version.DataWriteVersion;

import static org.midonet.midolman.state.zkManagers.VtepZkManager.MIN_VNI;

public class Setup {

    static final Logger log = LoggerFactory.getLogger(Setup.class);

    private static final String MIDONET_QDISC_CREATE = "midonet_qdisc_create";
    private static final String NOVA_QDISC_CREATE = "nova_qdisc_create";
    private static final String QDISC_DESTROY = "qdisc_destroy";

    private HierarchicalConfiguration config;

    private void run(String[] args) throws Exception {
        Options options = new Options();
        options.addOption("c", "configFile", true, "config file path");
        CommandLineParser parser = new GnuParser();
        CommandLine cl = parser.parse(options, args);
        String configFilePath = cl.getOptionValue('c', "./conf/midolman.conf");

        config = new HierarchicalINIConfiguration(configFilePath);

        args = cl.getArgs();
        if (args.length == 0)
            return;
        String command = args[0].toLowerCase();
        if (command.equals(MIDONET_QDISC_CREATE))
            setupTrafficPriorityQdiscsMidonet();
        else if (command.equals(NOVA_QDISC_CREATE))
            setupTrafficPriorityQdiscsNova();
        else if (command.equals(QDISC_DESTROY))
            removeTrafficPriorityQdiscs();
        else
            System.out.println("Unrecognized command. Exiting.");
    }

    private static List<String> getTopLevelPaths(PathBuilder pathMgr) {
        List<String> paths = new ArrayList<>();
        paths.add(pathMgr.getAdRoutesPath());
        paths.add(pathMgr.getBgpPath());
        paths.add(pathMgr.getBridgesPath());
        paths.add(pathMgr.getVlanBridgesPath());
        paths.add(pathMgr.getChainsPath());
        paths.add(pathMgr.getFiltersPath());
        paths.add(pathMgr.getRulesPath());
        paths.add(pathMgr.getTunnelPath());
        paths.add(pathMgr.getTunnelZonesPath());
        paths.add(pathMgr.getPortsPath());
        paths.add(pathMgr.getPortSetsPath());
        paths.add(pathMgr.getRoutersPath());
        paths.add(pathMgr.getRoutesPath());
        paths.add(pathMgr.getAgentPath());
        paths.add(pathMgr.getAgentPortPath());
        paths.add(pathMgr.getPortGroupsPath());
        paths.add(pathMgr.getIpAddrGroupsPath());
        paths.add(pathMgr.getHostsPath());
        paths.add(pathMgr.getTenantsPath());
        paths.add(pathMgr.getVersionsPath());
        paths.add(pathMgr.getVersionPath(DataWriteVersion.CURRENT));
        paths.add(pathMgr.getSystemStatePath());
        paths.add(pathMgr.getTraceConditionsPath());
        paths.add(pathMgr.getHealthMonitorsPath());
        paths.add(pathMgr.getLoadBalancersPath());
        paths.add(pathMgr.getPoolHealthMonitorMappingsPath());
        paths.add(pathMgr.getPoolMembersPath());
        paths.add(pathMgr.getPoolsPath());
        paths.add(pathMgr.getVipsPath());
        paths.add(pathMgr.getHealthMonitorLeaderDirPath());
        paths.add(pathMgr.getVtepsPath());
        paths.add(pathMgr.getVxLanPortIdsPath());
        paths.add(pathMgr.getLocksPath());
        paths.add(pathMgr.getLicensesPath());

        // Neutron paths
        paths.add(pathMgr.getNeutronPath());
        paths.add(pathMgr.getNeutronNetworksPath());
        paths.add(pathMgr.getNeutronSubnetsPath());
        paths.add(pathMgr.getNeutronPortsPath());
        paths.add(pathMgr.getNeutronRoutersPath());
        paths.add(pathMgr.getNeutronFloatingIpsPath());
        paths.add(pathMgr.getNeutronSecurityGroupsPath());
        paths.add(pathMgr.getNeutronSecurityGroupRulesPath());
        // Neutron LoadBalancer paths
        paths.add(pathMgr.getNeutronLoadBalancerPath());
        paths.add(pathMgr.getNeutronPoolsPath());
        paths.add(pathMgr.getNeutronVipsPath());
        paths.add(pathMgr.getNeutronMembersPath());
        paths.add(pathMgr.getNeutronHealthMonitorsPath());

        return paths;
    }

    public static void ensureZkDirectoryStructureExists(Directory rootDir,
                                                        String basePath)
        throws KeeperException, InterruptedException
    {
        ensureBasePathExists(rootDir, basePath);
        PathBuilder pathMgr = new PathBuilder(basePath);
        for (String path : Setup.getTopLevelPaths(pathMgr)) {
            rootDir.ensureHas(path, null);
        }
        rootDir.ensureHas(pathMgr.getVniCounterPath(),
                          Integer.toString(MIN_VNI).getBytes());
        rootDir.ensureHas(pathMgr.getWriteVersionPath(),
                          DataWriteVersion.CURRENT.getBytes());
    }

    public static void ensureBasePathExists(Directory rootDir,
                                            String basePath)
            throws KeeperException, InterruptedException {
        String currentPath = "";
        for (String part : basePath.split("/+")) {
            if (part.trim().isEmpty())
                continue;

            currentPath += "/" + part;
            try {
                if (!rootDir.has(currentPath)) {
                    log.debug("Adding " + currentPath);
                    rootDir.add(currentPath, null, CreateMode.PERSISTENT);
                }
            } catch (KeeperException.NodeExistsException ex) {
                // Don't exit even if the node exists.
                log.warn("doStart: {} already exists.", currentPath);
            }
        }
    }

    protected void setupTrafficPriorityQdiscsMidonet()
            throws IOException, URISyntaxException, InterruptedException {
        int markValue = 0x00ACCABA;  // Midokura's OUI.
        String iface = config.configurationAt("midolman")
                             .getString("control_interface", "eth0");

        // Add a prio qdisc to root, and have marked packets prioritized.
        Sudo.sudoExec("tc qdisc add dev " + iface + " root handle 1: prio");
        Sudo.sudoExec("tc filter add dev " + iface +
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

        // Add rules to mark Cassandra packets.
        String mcHosts = config.configurationAt("cassandra")
                               .getString("servers", "127.0.0.1:9170");
        for (String mcServer : mcHosts.split(",")) {
            String[] hostport = mcServer.split(":");
            setupTrafficPriorityRule(hostport[0], hostport[1]);
        }
    }

    protected void setupTrafficPriorityQdiscsNova()
            throws IOException, InterruptedException, URISyntaxException {
        FileReader confFile = new FileReader("/etc/nova/nova.conf");
        char[] confBytes = new char[5000];
        int confByteLength = confFile.read(confBytes);
        String[] allArgs = (new String(confBytes, 0, confByteLength)).split("\n");
        for (String arg : allArgs) {
            // RabbitMQ
            if (arg.startsWith("--rabbit_host")) {
                String[] flaghost = arg.split("=");
                setupTrafficPriorityRule(flaghost[1], "5672");
            }

            // mysql
            if (arg.startsWith("--sql_connection")) {
                String[] flagurl = arg.split("=");
                URI mysqlUrl = new URI(flagurl[1]);
                int port = mysqlUrl.getPort();
                if (port == -1)
                    port = 3306;
                setupTrafficPriorityRule(mysqlUrl.getHost(), Integer.toString(port));
            }

            // VNC
            if (arg.startsWith("--sql_connection")) {
                String[] flagurl = arg.split("=");
                URI vncUrl = new URI(flagurl[1]);
                int port = vncUrl.getPort();
                if (port == -1)
                    port = 6080;
                setupTrafficPriorityRule(vncUrl.getHost(), Integer.toString(port));
            }

            // EC2
            if (arg.startsWith("--ec2_url")) {
                String[] flagurl = arg.split("=");
                URI ec2Url = new URI(flagurl[1]);
                int port = ec2Url.getPort();
                if (port == -1)
                    port = 8773;
                setupTrafficPriorityRule(ec2Url.getHost(), Integer.toString(port));
            }
        }
    }

    protected void removeTrafficPriorityQdiscs()
            throws IOException, InterruptedException {
        // Clear existing qdiscs
        String iface = config.configurationAt("midolman")
                             .getString("control_interface", "eth0");
        Sudo.sudoExec("tc qdisc del dev " + iface + " root");
    }

    protected static void setupTrafficPriorityRule(String host, String port)
            throws IOException, InterruptedException {
        int markValue = 0x00ACCABA;  // Midokura's OUI.
        Sudo.sudoExec(
                "iptables -t mangle -A POSTROUTING -p tcp -m tcp -d " +
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
