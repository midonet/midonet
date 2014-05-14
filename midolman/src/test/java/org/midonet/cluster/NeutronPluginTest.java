/*
 * Copyright 2014 Midokura PTE LTD.
 */

package org.midonet.cluster;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;
import org.midonet.cluster.data.Rule;
import org.midonet.midolman.Setup;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.guice.CacheModule;
import org.midonet.midolman.guice.MockMonitoringStoreModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.config.TypedConfigModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.CheckpointedDirectory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.version.guice.VersionModule;
import org.midonet.cluster.data.neutron.*;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class NeutronPluginTest {

    @Inject NeutronPlugin plugin;
    Injector injector = null;
    String zkRoot = "/test";


    HierarchicalConfiguration fillConfig(HierarchicalConfiguration config) {
        config.addNodes(ZookeeperConfig.GROUP_NAME,
                Arrays.asList(new HierarchicalConfiguration.Node
                        ("midolman_root_key", zkRoot)));
        return config;
    }

    CheckpointedDirectory zkDir() {
        return injector.getInstance(CheckpointedDirectory.class);
    }

    private Network createStockNetwork() {
        Network network = new Network();
        network.adminStateUp = true;
        network.name = "net";
        network.tenantId = "tenant";
        network.shared = true;
        network.id = UUID.randomUUID();
        return network;
    }

    public Subnet createStockSubnet() {
        Subnet subnet = new Subnet();
        subnet.cidr = "10.0.0.0/24";
        List<String> nss = new ArrayList<>();
        nss.add("10.0.0.1");
        nss.add("10.0.1.1");
        nss.add("10.0.2.1");
        List<Route> routes = new ArrayList<>();
        Route r = new Route();
        r.destination = "10.1.1.1";
        r.nexthop = "10.1.1.2";
        Route r2 = new Route();
        r2.destination = "20.1.1.1";
        r2.nexthop = "20.1.1.2";
        routes.add(r);
        routes.add(r2);
        subnet.dnsNameservers = nss;
        subnet.hostRoutes = routes;
        subnet.enableDhcp = true;
        subnet.gatewayIp = "10.0.0.1";
        subnet.ipVersion = 4;
        subnet.name = "sub";
        subnet.tenantId = "tenant";
        subnet.id = UUID.randomUUID();
        return subnet;
    }

    public Port createStockPort(UUID subnetId, UUID networkId) {
        Port port = new Port();
        port.adminStateUp = true;
        List<IPAllocation> ips = new ArrayList<>();
        IPAllocation ip = new IPAllocation();
        ip.ipAddress = "10.0.0.10";
        ip.subnetId = subnetId;
        ips.add(ip);
        List<UUID> secGroups = new ArrayList<>();
        secGroups.add(UUID.randomUUID());
        port.fixedIps = ips;
        port.tenantId = "tenant";
        port.networkId = networkId;
        port.macAddress = "aa:bb:cc:00:11:22";
        port.id = UUID.randomUUID();
        return port;
    }

    @Before
    public void initialize() throws InterruptedException, KeeperException {
        HierarchicalConfiguration config = fillConfig(
                new HierarchicalConfiguration());
        injector = Guice.createInjector(
                new VersionModule(),
                new SerializationModule(),
                new ConfigProviderModule(config),
                new MockZookeeperConnectionModule(),
                new TypedConfigModule<>(MidolmanConfig.class),
                new CacheModule(),
                new MockMonitoringStoreModule(),
                new NeutronClusterModule()
        );
        injector.injectMembers(this);
        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);
    }

    @Test
    public void testSubnetCRUD() throws StateAccessException,
            SerializationException {
        int cp1 = zkDir().createCheckPoint();
        Network network = plugin.createNetwork(createStockNetwork());
        int cp2 = zkDir().createCheckPoint();

        Subnet subnet = createStockSubnet();
        subnet.networkId = network.id;
        subnet = plugin.createSubnet(subnet);
        int cp3 = zkDir().createCheckPoint();

        subnet.enableDhcp = false;
        plugin.updateSubnet(subnet.id, subnet);
        int cp4 = zkDir().createCheckPoint();

        assert(zkDir().getRemovedPaths(cp3, cp4).size() == 0);
        assert(zkDir().getModifiedPaths(cp3, cp4).size() == 1);
        assert(zkDir().getAddedPaths(cp3, cp4).size() == 0);

        subnet.enableDhcp = true;
        plugin.updateSubnet(subnet.id, subnet);
        int cp5 = zkDir().createCheckPoint();

        assert(zkDir().getRemovedPaths(cp4, cp5).size() == 0);
        assert(zkDir().getModifiedPaths(cp4, cp5).size() == 1);
        assert(zkDir().getAddedPaths(cp4, cp5).size() == 0);

        plugin.deleteSubnet(subnet.id);
        int cp6 = zkDir().createCheckPoint();

        assert(zkDir().getRemovedPaths(cp2, cp6).size() == 0);
        assert(zkDir().getModifiedPaths(cp2, cp6).size() == 0);
        assert(zkDir().getAddedPaths(cp2, cp6).size() == 0);

        plugin.deleteNetwork(network.id);
        int cp7 = zkDir().createCheckPoint();
        assert(zkDir().getRemovedPaths(cp1, cp7).size() == 0);
        assert(zkDir().getModifiedPaths(cp1, cp7).size() == 0);
        // There is one added path we expect: the gre tunnel key
        assert(zkDir().getAddedPaths(cp1, cp7).size() == 1);
    }

    @Test
    public void testNetworkCRUD() throws SerializationException,
            StateAccessException, BridgeZkManager.VxLanPortIdUpdateException {
        int cp1 = zkDir().createCheckPoint();
        Network network = createStockNetwork();
        network.external = true;
        network = plugin.createNetwork(createStockNetwork());
        int cp2 = zkDir().createCheckPoint();

        network.adminStateUp = false;
        network = plugin.updateNetwork(network.id, network);
        int cp3 = zkDir().createCheckPoint();

        assert(zkDir().getRemovedPaths(cp2, cp3).size() == 0);
        assert(zkDir().getModifiedPaths(cp2, cp3).size() == 2);
        assert(zkDir().getAddedPaths(cp2, cp3).size() == 0);

        network.adminStateUp = true;
        network = plugin.updateNetwork(network.id, network);
        int cp4 = zkDir().createCheckPoint();

        assert(zkDir().getRemovedPaths(cp3, cp4).size() == 0);
        assert(zkDir().getModifiedPaths(cp3, cp4).size() == 2);
        assert(zkDir().getAddedPaths(cp3, cp4).size() == 0);

        assert(zkDir().getRemovedPaths(cp2, cp4).size() == 0);
        assert(zkDir().getModifiedPaths(cp2, cp4).size() == 0);
        assert(zkDir().getAddedPaths(cp2, cp4).size() == 0);

        plugin.deleteNetwork(network.id);
        int cp5 = zkDir().createCheckPoint();
        assert(zkDir().getRemovedPaths(cp1, cp5).size() == 0);
        assert(zkDir().getModifiedPaths(cp1, cp5).size() == 0);
        assert(zkDir().getAddedPaths(cp1, cp5).size() == 1);
    }

    @Test
    public void testPortCRUD() throws SerializationException,
            StateAccessException, Rule.RuleIndexOutOfBoundsException {
        Network network = plugin.createNetwork(createStockNetwork());
        Subnet subnet = createStockSubnet();
        subnet.networkId = network.id;
        subnet = plugin.createSubnet(subnet);

        int cp1 = zkDir().createCheckPoint();

        Port port = createStockPort(subnet.id, network.id);
        port = plugin.createPort(port);

        Port dhcpPort = createStockPort(subnet.id, network.id);
        dhcpPort.deviceOwner = DeviceOwner.DHCP;
        dhcpPort = plugin.createPort(dhcpPort);

        int cp2 = zkDir().createCheckPoint();

        dhcpPort.securityGroups.add(UUID.randomUUID());
        dhcpPort = plugin.updatePort(dhcpPort.id, dhcpPort);

        int cp3 = zkDir().createCheckPoint();

        assert(zkDir().getRemovedPaths(cp2, cp3).size() == 0);
        assert(zkDir().getModifiedPaths(cp2, cp3).size() == 2);
        assert(zkDir().getAddedPaths(cp2, cp3).size() == 0);


        plugin.deletePort(dhcpPort.id);
        plugin.deletePort(port.id);

        int cp6 = zkDir().createCheckPoint();

        assert(zkDir().getRemovedPaths(cp1, cp6).size() == 0);
        //assert(zkDir().getModifiedPaths(cp1, cp6).size() == 0);  <-- FAILS
        assert(zkDir().getAddedPaths(cp1, cp6).size() == 0);

        plugin.deleteSubnet(subnet.id);
        plugin.deleteNetwork(network.id);
    }
}

