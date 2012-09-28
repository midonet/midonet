/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midonet.functional_test;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static java.lang.String.format;

import com.midokura.midonet.functional_test.mocks.MockMgmtStarter;
import com.midokura.midonet.functional_test.utils.*;
import com.midokura.util.Waiters;
import me.prettyprint.cassandra.service.CassandraHostConfigurator;
import me.prettyprint.hector.api.Cluster;
import me.prettyprint.hector.api.factory.HFactory;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.commons.io.FileUtils;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;

import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;
import com.midokura.midonet.functional_test.topology.MaterializedRouterPort;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.Port;
import com.midokura.midonet.functional_test.topology.Tenant;
import com.midokura.midonet.functional_test.vm.VMController;
import com.midokura.tools.timed.Timed;
import com.midokura.util.SystemHelper;
import com.midokura.util.process.ProcessHelper;
import org.yaml.snakeyaml.Yaml;

import static com.midokura.tools.timed.Timed.newTimedExecution;

/**
 * @author Mihai Claudiu Toader  <mtoader@midokura.com>
 *         Date: 12/7/11
 */
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
                ProcessHelper.executeLocalCommandLine("which " + zkClientPath);

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

    public static void removeBridge(OvsBridge ovsBridge) {
        if (ovsBridge != null) {
            ovsBridge.remove();
        }
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
        try {
            HierarchicalConfiguration midolmanConf = new HierarchicalINIConfiguration(testConfFile);
            // get the zookeeper port from the configuration.
            String zookeperHost = midolmanConf.getString("zookeeper.zookeeper_hosts");
            int zookeperPort = Integer.parseInt(zookeperHost.split(":")[1]);
            log.info("Starting zookeeper at port: " + zookeperPort);
            return EmbeddedZKLauncher.start(zookeperPort);
        } catch (ConfigurationException e) {
            log.error("Error in the configuration file. ");
            return -1;
        }
    }

    public static void stopEmbeddedZookeeper() {
        EmbeddedZKLauncher.stop();
    }

    /**
     * This method starts the embedded cassandra with the non-default port.
     * WARN This method does not work fine when using 'sudo'.
     * TODO marc: clean and investigate why it's failing.
     * @param cassandraConfiguration
     * @param testConfiguration
     */
    public static void startCassandra(String cassandraConfiguration, String testConfiguration) {
            // read the midolman configuration file.
            File testConfFile = new File(testConfiguration);

            if (!testConfFile.exists()) {
                log.error("Configuration file does not exist: {}", testConfiguration);
                return;
            }
            try {
                HierarchicalConfiguration midolmanConf = new HierarchicalINIConfiguration(testConfFile);
                String cassandraHost = midolmanConf.getString("cassandra.servers");
               int cassandraPort = Integer.parseInt(cassandraHost.split(":")[1]);
                String cassandraClusterName = midolmanConf.getString("cassandra.cluster");

               // get the cassandra port from both configuration files.
               File cassandraConf = new File("midolmanj_runtime_configurations/cassandra.yaml");
               //assertTrue("Cassandra configuration file exists", cassandraConf.exists());
               Yaml yamlParser = new Yaml();
              Map parsedConf = (Map) yamlParser.load(new FileReader(cassandraConf));

              final Integer yamlCassandraPort = (Integer) parsedConf.get("rpc_port");
              final String yamlClusterName = (String) parsedConf.get("cluster_name");
              //assertTrue("Cassandra ports are not the same in cassandra.yaml and the test configuration.",
              // yamlCassandraPort == cassandraPort);
              //assertTrue("Cassandra cluster names are not the same in cassandra.yaml and the test configuration",
              // yamlClusterName.matches(cassandraClusterName));
              EmbeddedCassandraServerHelper.startEmbeddedCassandra(cassandraConfiguration);
              // make sure it's clean.
                EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
               Waiters.waitFor("Cassandra must be up & running", new Timed.Execution<Boolean> () {
                @Override
                protected void _runOnce() throws Exception {
                    log.info("Waiting for cassandra...");
                    Cluster cluster = HFactory.getOrCreateCluster(yamlClusterName, new CassandraHostConfigurator("localhost:"+yamlCassandraPort));
                    setCompleted(cluster.getConnectionManager() != null &&
                            cluster.getConnectionManager().getActivePools() != null &&
                            cluster.getConnectionManager().getActivePools().size() == 1);
                }
            } );
        } catch (Exception e) {
            log.error("Failed to start embedded Cassandra.", e);
        }

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
        EmbeddedCassandraServerHelper.stopEmbeddedCassandra();
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
    public static void startEmbeddedMidolman(String configFile) {
        midolman = new EmbeddedMidolman();
        try {
            midolman.startMidolman(configFile);
        } catch (Exception e) {
            log.error("Could not stack Midolmanj", e);
        }
        // TODO wait until the agents are started.
    }

    /**
     * Stops midolman (as a separate process)
     * @param ml
     */
    public static void stopMidolman(MidolmanLauncher ml) {
        ml.stop();
    }
}
