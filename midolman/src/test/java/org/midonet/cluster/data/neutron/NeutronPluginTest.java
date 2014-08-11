/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import java.util.Arrays;
import java.util.UUID;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.curator.test.TestingServer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import org.midonet.cluster.data.Rule;
import org.midonet.cluster.services.MidostoreSetupService;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.guice.cluster.DataClientModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.version.guice.VersionModule;
import org.midonet.packets.MAC;

public abstract class NeutronPluginTest {

    // Zookeeper configurations
    private static TestingServer server;
    private static final int ZK_PORT = 12181;
    private static final String ZK_CONN_STRING = "127.0.0.1:" + ZK_PORT;

    // Default tenant values
    protected static final String TENANT_ID = "tenant";
    protected static final String ADMIN_ID = "admin";

    // Default SG and SG rules
    private static final UUID SG_ID = UUID.randomUUID();
    protected static final SecurityGroup securityGroup = new SecurityGroup(
        SG_ID, TENANT_ID, "sg", "sg_desc",
        Arrays.asList(
            new SecurityGroupRule(
                UUID.randomUUID(), SG_ID, RuleDirection.EGRESS,
                RuleEthertype.IPv4, RuleProtocol.TCP),
            new SecurityGroupRule(
                UUID.randomUUID(), SG_ID, RuleDirection.INGRESS,
                RuleEthertype.IPv4, RuleProtocol.TCP)
        ));

    // Default network
    protected static final Network network = new Network(
        UUID.randomUUID(), TENANT_ID, "net", false);

    // Default subnet
    protected static final Subnet subnet = new Subnet(
        UUID.randomUUID(), network.id, TENANT_ID, "sub", "10.0.0.0/24", 4,
        "10.0.0.1",
        Arrays.asList(new IPAllocationPool("10.0.0.2", "10.0.0.100")),
        Arrays.asList("8.8.8.8", "8.8.4.4"),
        Arrays.asList(new Route("20.0.0.0/24", "20.0.0.1")), true);

    // Default port
    protected static final Port port = new Port(
        UUID.randomUUID(), network.id, TENANT_ID, "port",
        MAC.random().toString(),
        Arrays.asList(new IPAllocation("10.0.0.5", subnet.id)), null, null,
        Arrays.asList(SG_ID));

    // DHCP port
    protected static final Port dhcpPort = new Port(
        UUID.randomUUID(), network.id, TENANT_ID, "dhcp_port",
        MAC.random().toString(),
        Arrays.asList(new IPAllocation("10.0.0.2", subnet.id)),
        DeviceOwner.DHCP, null, null);

    // Default external network
    protected static final Network extNetwork = new Network(
        UUID.randomUUID(), ADMIN_ID, "ext-net", true);

    // Default external subnet
    protected static final Subnet extSubnet = new Subnet(
        UUID.randomUUID(), extNetwork.id, ADMIN_ID, "ext-sub", "200.0.0.0/24",
        4, "200.0.0.1",
        Arrays.asList(new IPAllocationPool("200.0.0.2", "200.0.0.100")),
        null, null, true);

    // Default gateway port
    protected static final Port gwPort = new Port(
        UUID.randomUUID(), extNetwork.id, ADMIN_ID, "gw_port",
        MAC.random().toString(),
        Arrays.asList(new IPAllocation("200.0.0.1", extSubnet.id)),
        DeviceOwner.ROUTER_GW, null, null);

    // Default router
    protected static final Router router = new Router(
        UUID.randomUUID(), TENANT_ID, "router", true, gwPort.id,
        new ExternalGatewayInfo(extNetwork.id, true));

    // Default router port
    protected static final Port routerPort = new Port(
        UUID.randomUUID(), network.id, TENANT_ID, "router_port",
        MAC.random().toString(),
        Arrays.asList(new IPAllocation("10.0.0.1", subnet.id)),
        DeviceOwner.ROUTER_INTF, router.id.toString(), null);

    // Default router interface
    protected static final RouterInterface routerInterface =
        new RouterInterface(router.id, TENANT_ID, routerPort.id, subnet.id);

    // Default floating IP
    protected static final FloatingIp floatingIp = new FloatingIp(
        UUID.randomUUID(), TENANT_ID, router.id, "200.0.0.5", port.id,
        "10.0.0.5");

    private Injector injector;
    protected NeutronPlugin plugin;

    protected Directory getDirectory() {
        return injector.getInstance(Directory.class);
    }

    private MidostoreSetupService getMidostoreService() {
        return injector.getInstance(MidostoreSetupService.class);
    }

    protected PathBuilder getPathBuilder() {
        return injector.getInstance(PathBuilder.class);
    }

    private static HierarchicalConfiguration getConfig(String zkRoot) {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        config.addNodes(ZookeeperConfig.GROUP_NAME,
                        Arrays.asList(
                            new HierarchicalConfiguration.Node(
                                "midolman_root_key", zkRoot),
                            new HierarchicalConfiguration.Node(
                                "zookeeper_hosts", ZK_CONN_STRING)));
        return config;
    }

    private void initializeDeps(final String zkRoot) {

        injector = Guice.createInjector(
            new VersionModule(),
            new SerializationModule(),
            new ConfigProviderModule(getConfig(zkRoot)),
            new ZookeeperConnectionModule(),
            new DataClientModule(),
            new NeutronClusterModule(),
            new AbstractModule() {
                @Override
                protected void configure() {
                    bind(NeutronPlugin.class);
                }
            }
        );
    }

    @BeforeClass
    public static void classSetUp() throws Exception {
        server = new TestingServer(ZK_PORT);
    }

    @AfterClass
    public static void classTearDown() throws Exception {
        server.close();
    }

    @Before
    public void setUp() throws Exception {

        // Run the test on a new directory
        String zkRoot = "/test_" + UUID.randomUUID();
        initializeDeps(zkRoot);

        getMidostoreService().startAndWait();
        plugin = injector.getInstance(NeutronPlugin.class);

        // Set up a basic scenario for all the tests for now
        setUpBasicScenario();
    }

    @After
    public void tearDown() throws Exception {
        getMidostoreService().stopAndWait();
    }

    public void setUpBasicScenario()
        throws Rule.RuleIndexOutOfBoundsException, SerializationException,
               StateAccessException {

        // Create a security group with default rules
        plugin.createSecurityGroup(securityGroup);

        // Create a network, subnet and a port(with fixed IP and default SG)
        plugin.createNetwork(network);
        plugin.createSubnet(subnet);
        plugin.createPort(dhcpPort);
        plugin.createPort(port);

        // Create an external network and subnet
        plugin.createNetwork(extNetwork);
        plugin.createSubnet(extSubnet);

        // Create a router and set the gateway
        plugin.createPort(gwPort);
        plugin.createRouter(router);

        // Link the network to the router
        plugin.createPort(routerPort);
        plugin.addRouterInterface(router.id, routerInterface);

        // Create a floating IP to associate with a fixed IP
        plugin.createFloatingIp(floatingIp);
    }
}
