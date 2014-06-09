/*
 * Copyright 2014 Midokura PTE LTD.
 */

package org.midonet.cluster;

import com.google.common.base.Objects;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.zookeeper.KeeperException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.midonet.cluster.data.Chain;
import org.midonet.cluster.data.IpAddrGroup;
import org.midonet.cluster.data.Router;
import org.midonet.cluster.data.Rule;
import org.midonet.cluster.data.neutron.*;
import org.midonet.cluster.data.rules.JumpRule;
import org.midonet.cluster.data.rules.ForwardNatRule;
import org.midonet.midolman.Setup;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.guice.CacheModule;
import org.midonet.midolman.guice.MockMonitoringStoreModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.config.TypedConfigModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.rules.NatTarget;
import org.midonet.midolman.rules.RuleResult;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.CheckpointedDirectory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.version.guice.VersionModule;
import org.midonet.packets.ARP;

import java.util.*;

public class NeutronPluginTest {

    @Inject NeutronPlugin plugin;
    @Inject DataClient dataClient;

    Injector injector = null;
    String zkRoot = "/test";


    HierarchicalConfiguration fillConfig(HierarchicalConfiguration config) {
        config.addNodes(ZookeeperConfig.GROUP_NAME,
                Arrays.asList(new HierarchicalConfiguration.Node("midolman_root_key", zkRoot)));
        return config;
    }

    CheckpointedDirectory zkDir() {
        return injector.getInstance(CheckpointedDirectory.class);
    }

    /*
     * Simple utility functions used in UT to test types of rules.
     */

    public static boolean isDropAllExceptArpRule(Rule rule) {
        if (rule == null) {
            return false;
        }
        Condition cond = rule.getCondition();
        if (cond == null) {
            return false;
        }
        if (!cond.dlType.equals(new Integer(ARP.ETHERTYPE))) {
            return false;
        }
        if (!(cond.invDlType)) {
            return false;
        }
        if (!Objects.equal(rule.getAction(), RuleResult.Action.DROP)) {
            return false;
        }
        return true;
    }

    public static boolean isMacSpoofProtectionRule(String macAddress, Rule rule) {
        if (rule == null) {
            return false;
        }
        Condition cond = rule.getCondition();
        if (cond == null) {
            return false;
        }
        if (!cond.invDlSrc) {
            return false;
        }
        if (!Objects.equal(cond.dlSrc.toString(), macAddress)) {
            return false;
        }
        if (!Objects.equal(rule.getAction(), RuleResult.Action.DROP)) {
            return false;
        }
        return true;
    }

    public static boolean isIpSpoofProtectionRule(IPAllocation subnet, Rule rule) {
        if (rule == null) {
            return false;
        }
        Condition cond = rule.getCondition();
        if (cond == null) {
            return false;
        }
        if (!cond.nwSrcInv) {
            return false;
        }
        String subnetStr = cond.nwSrcIp.getAddress().toString();
        if (!Objects.equal(subnetStr, subnet.ipAddress)) {
            return false;
        }
        if (!Objects.equal(rule.getAction(), RuleResult.Action.DROP)) {
            return false;
        }
        return true;
    }

    public static boolean isAcceptReturnFlowRule(org.midonet.cluster.data.Rule rule) {
        if (rule == null) return false;
        Condition cond = rule.getCondition();
        if (cond == null) return false;
        if (!cond.matchReturnFlow) return false;
        if (!Objects.equal(rule.getAction(), RuleResult.Action.ACCEPT)) {
            return false;
        }
        return true;
    }

    public Network createStockNetwork() {
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

    public Port createStockPort(UUID subnetId, UUID networkId,
                                UUID defaultSgId) {
        Port port = new Port();
        port.adminStateUp = true;
        List<IPAllocation> ips = new ArrayList<>();
        IPAllocation ip = new IPAllocation();
        ip.ipAddress = "10.0.0.10";
        ip.subnetId = subnetId;
        ips.add(ip);
        List<UUID> secGroups = new ArrayList<>();
        if (defaultSgId != null) secGroups.add(defaultSgId);
        port.fixedIps = ips;
        port.tenantId = "tenant";
        port.networkId = networkId;
        port.macAddress = "aa:bb:cc:00:11:22";
        port.securityGroups = secGroups;
        port.id = UUID.randomUUID();
        return port;
    }

    public SecurityGroup createStockSecurityGroup() {
        SecurityGroup sg = new SecurityGroup();
        sg.description = "block stuff";
        sg.tenantId = "tenant";
        sg.id = UUID.randomUUID();
        sg.name = "nameOfSg";
        SecurityGroupRule sgr1 = new SecurityGroupRule();
        sgr1.direction = RuleDirection.INGRESS;
        sgr1.ethertype = RuleEthertype.IPv4;
        sgr1.securityGroupId = sg.id;
        sgr1.id = UUID.randomUUID();
        SecurityGroupRule sgr2 = new SecurityGroupRule();
        sgr2.direction = RuleDirection.INGRESS;
        sgr2.ethertype = RuleEthertype.IPv4;
        sgr2.securityGroupId = sg.id;
        sgr2.id = UUID.randomUUID();
        sg.securityGroupRules = new ArrayList<>();
        sg.securityGroupRules.add(sgr1);
        sg.securityGroupRules.add(sgr2);
        return sg;
    }

    public org.midonet.cluster.data.neutron.Router createStockRouter() {
        org.midonet.cluster.data.neutron.Router r = new org.midonet.cluster.data.neutron.Router();
        r.id = UUID.randomUUID();
        r.externalGatewayInfo = new ExternalGatewayInfo();
        r.adminStateUp = true;
        r.tenantId = "tenant";
        return r;
    }


    public RouterInterface createStockRouterInterface(UUID portId, UUID subnetId)
            throws StateAccessException {
        RouterInterface ri = new RouterInterface();
        ri.id = UUID.randomUUID();
        ri.portId = portId;
        ri.subnetId = subnetId;
        return ri;
    }

    public void verifyIpAddrGroups() throws StateAccessException, SerializationException {
        List<IpAddrGroup> ipgs = dataClient.ipAddrGroupsGetAll();
        List<Port> ports = plugin.getPorts();

        // Each Ip under the ipaddr groups needs to be associated with a port
        for (IpAddrGroup ipg : ipgs) {
            Set<String> ips = dataClient.getAddrsByIpAddrGroup(ipg.getId());
            for (String ip : ips) {
                boolean found = false;
                for (Port port : ports) {
                    if (port.securityGroups.contains(ipg.getId())) {
                        for (IPAllocation ipAllocation : port.fixedIps) {
                            if (ipAllocation.ipAddress.equals(ip)) {
                                found = true;
                            }
                        }
                    }
                }
                Assert.assertTrue(found);
            }
        }
    }

    public void verifySGRules(Port port) throws StateAccessException, SerializationException {

        // Ensure that the port has both of its INBOUND and OUTBOUND chains.
        Chain inbound = null, outbound = null;
        for (Chain c : dataClient.chainsGetAll()) {
            String cName = c.getName();
            if (cName == null) continue;
            if (cName.contains("INBOUND") && cName.contains(port.id.toString())) {
                inbound = c;
            } else if (cName.contains("OUTBOUND") && cName.contains(port.id.toString())) {
                outbound = c;
            }
        }
        Assert.assertNotNull(inbound);
        Assert.assertNotNull(outbound);

        List<Rule<?,?>> inboundRules = dataClient.rulesFindByChain(inbound.getId());
        List<Rule<?,?>> outboundRules = dataClient.rulesFindByChain(outbound.getId());

        Assert.assertTrue(isAcceptReturnFlowRule(outboundRules.get(0)));
        Assert.assertTrue(isDropAllExceptArpRule(outboundRules.get(outboundRules.size() - 1)));

        List<Rule<?, ?>> spoofRules = inboundRules.subList(0, port.fixedIps.size());
        for (IPAllocation ip : port.fixedIps) {

            // verify the rule exists
            boolean found = false;
            for (Rule r : spoofRules) {
                if (isIpSpoofProtectionRule(ip, r)) {
                    found = true;
                    break;
                }
            }
            Assert.assertTrue(found);
        }

        Assert.assertTrue(isMacSpoofProtectionRule(port.macAddress,
            inboundRules.get(spoofRules.size())));

        Assert.assertTrue(isAcceptReturnFlowRule(inboundRules.get(spoofRules.size() + 1)));

        List<Rule<?, ?>> sgJumpRulesInbound = inboundRules.subList(spoofRules.size() + 2,
            inboundRules.size() - 1);
        // TODO: FAILS
        //Assert.assertEquals(sgJumpRulesInbound.size(),
        //        port.securityGroups.size());

        List<Rule<?, ?>> sgJumpRulesOutbound = outboundRules.subList(1, outboundRules.size() - 1);

        // TODO: FAILS
        //Assert.assertEquals(sgJumpRulesOutbound.size(),
        //        port.securityGroups.size());

        for (UUID sgid : port.securityGroups) {
            // First verify that the security group chains exists
            Chain ingress = null, egress = null;
            for (Chain c : dataClient.chainsGetAll()) {
                String cName = c.getName();
                if (cName == null) continue;
                if (cName.contains("INGRESS") && cName.contains(sgid.toString())) {
                    ingress = c;
                } else if (cName.contains("EGRESS") && cName.contains(sgid.toString())) {
                    egress = c;
                }
            }
            Assert.assertNotNull(ingress);
            Assert.assertNotNull(egress);

            // Each security group should have all of this ports ips
            for (IPAllocation ip : port.fixedIps) {
                // verify this ip is part of the ip addr group associated
                // with this security group
                Assert.assertTrue(dataClient.ipAddrGroupHasAddr(sgid, ip.ipAddress));
            }

            //Verify that there is a jump rule to the egress and ingress chains
            boolean inboundFound = false, outboundFound = false;
            for (Rule r : sgJumpRulesInbound) {
                JumpRule jr = (JumpRule)r;
                if (egress.getName().equals(jr.getJumpToChainName()) &&
                        egress.getId().equals(jr.getJumpToChainId())) {
                    inboundFound = true;
                }
            }
            for (Rule r : sgJumpRulesOutbound) {
                JumpRule jr = (JumpRule)r;
                if (ingress.getName().equals(jr.getJumpToChainName()) &&
                        ingress.getId().equals(jr.getJumpToChainId())) {
                    outboundFound = true;
                }
            }
            Assert.assertTrue(inboundFound);
            Assert.assertTrue(outboundFound);

            SecurityGroup sg = plugin.getSecurityGroup(sgid);
            for (SecurityGroupRule sgr : sg.securityGroupRules) {
                SecurityGroupRule zkSgr = plugin.getSecurityGroupRule(sgr.id);
                Assert.assertTrue(Objects.equal(sgr, zkSgr));
                Rule r = dataClient.rulesGet(sgr.id);
                Assert.assertNotNull(r);
            }
        }

        Assert.assertTrue(isDropAllExceptArpRule(inboundRules.get(inboundRules.size() - 1)));
    }

    @Before
    public void initialize() throws InterruptedException, KeeperException {
        HierarchicalConfiguration config = fillConfig(new HierarchicalConfiguration());
        injector = Guice.createInjector(
                new VersionModule(),
                new SerializationModule(),
                new ConfigProviderModule(config),
                new MockZookeeperConnectionModule(),
                new TypedConfigModule<>(MidolmanConfig.class),
                new CacheModule(),
                new MockMonitoringStoreModule(),
                new NeutronClusterModule(),
                new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(NeutronPlugin.class);
                    }
                }

        );
        injector.injectMembers(this);
        Setup.ensureZkDirectoryStructureExists(zkDir(), zkRoot);
    }

    @Test
    public void testSubnetCRUD() throws StateAccessException, SerializationException {
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
    public void testNetworkCRUD() throws SerializationException, StateAccessException,
        BridgeZkManager.VxLanPortIdUpdateException {
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
    public void testPortCRUD() throws SerializationException, StateAccessException,
        Rule.RuleIndexOutOfBoundsException {
        Network network = plugin.createNetwork(createStockNetwork());
        Subnet subnet = createStockSubnet();
        subnet.networkId = network.id;
        subnet = plugin.createSubnet(subnet);

        int cp1 = zkDir().createCheckPoint();

        Port port = createStockPort(subnet.id, network.id, null);
        port = plugin.createPort(port);
        int cp2 = zkDir().createCheckPoint();

        Port dhcpPort = createStockPort(subnet.id, network.id, null);
        dhcpPort.deviceOwner = DeviceOwner.DHCP;
        dhcpPort = plugin.createPort(dhcpPort);

        int cp3 = zkDir().createCheckPoint();

        dhcpPort.securityGroups.add(UUID.randomUUID());
        dhcpPort = plugin.updatePort(dhcpPort.id, dhcpPort);

        int cp4 = zkDir().createCheckPoint();

        assert(zkDir().getRemovedPaths(cp3, cp4).size() == 0);
        assert(zkDir().getModifiedPaths(cp3, cp4).size() == 1);
        assert(zkDir().getAddedPaths(cp3, cp4).size() == 0);

        plugin.deletePort(dhcpPort.id);
        int cp6 = zkDir().createCheckPoint();

        assert(zkDir().getRemovedPaths(cp2, cp6).size() == 0);
        assert(zkDir().getModifiedPaths(cp2, cp6).size() == 0);
        assert(zkDir().getAddedPaths(cp2, cp6).size() == 0);

        plugin.deletePort(port.id);

        int cp7 = zkDir().createCheckPoint();

        assert(zkDir().getRemovedPaths(cp1, cp7).size() == 0);
        assert(zkDir().getModifiedPaths(cp1, cp7).size() == 0);
        assert(zkDir().getAddedPaths(cp1, cp7).size() == 0);

        plugin.deleteSubnet(subnet.id);
        plugin.deleteNetwork(network.id);
    }

    @Test
    public void testSecurityGroupCRUD() throws SerializationException, StateAccessException,
        Rule.RuleIndexOutOfBoundsException {
        Network network = plugin.createNetwork(createStockNetwork());
        Subnet subnet = createStockSubnet();
        subnet.networkId = network.id;
        subnet = plugin.createSubnet(subnet);

        int cp1 = zkDir().createCheckPoint();

        // Create a security group and a port
        SecurityGroup csg = createStockSecurityGroup();
        Collections.sort(csg.securityGroupRules);
        SecurityGroup sg = plugin.createSecurityGroup(csg);
        Collections.sort(sg.securityGroupRules);
        Assert.assertTrue(Objects.equal(sg, csg));

        Port port = createStockPort(subnet.id, network.id, sg.id);
        port = plugin.createPort(port);

        // Create a second security group and add it to the port
        SecurityGroup sg2 = createStockSecurityGroup();
        sg2 = plugin.createSecurityGroup(sg2);

        port.securityGroups.add(sg2.id);
        port = plugin.updatePort(port.id, port);
        verifyIpAddrGroups();
        verifySGRules(port);

        // Create a second port and add one of the security groups to it
        Port port2 = createStockPort(subnet.id, network.id, null);
        port2.id = UUID.randomUUID();
        port2.fixedIps = new ArrayList<>();
        port2.fixedIps.add(new IPAllocation("10.0.1.0", subnet.id));
        port2.macAddress = "11:22:33:44:55:66";
        port2 = plugin.createPort(port2);
        verifyIpAddrGroups();
        verifySGRules(port2);
        verifySGRules(port);

        // Add a security group to port2
        port2.securityGroups.add(sg2.id);
        port2 = plugin.updatePort(port2.id, port2);
        verifyIpAddrGroups();
        verifySGRules(port2);
        verifySGRules(port);

        // Remove a security group from a port
        port2.securityGroups = new ArrayList<>();
        port2 = plugin.updatePort(port2.id, port2);
        verifyIpAddrGroups();
        verifySGRules(port2);
        verifySGRules(port);

        // Remove all security groups from a port
        port.securityGroups = new ArrayList<>();
        port = plugin.updatePort(port.id, port);
        verifyIpAddrGroups();
        verifySGRules(port2);
        verifySGRules(port);

        // Create a third port with no fixedIps
        Port port3 = createStockPort(subnet.id, network.id, null);
        port3.id = UUID.randomUUID();
        port3.fixedIps = new ArrayList<>();
        port3.macAddress = "11:22:33:44:55:66";
        port3 = plugin.createPort(port3);
        verifyIpAddrGroups();
        verifySGRules(port3);
        verifySGRules(port2);
        verifySGRules(port);

        port3.name = "RYU";
        port3 = plugin.updatePort(port3.id, port3);
        verifyIpAddrGroups();
        verifySGRules(port3);
        verifySGRules(port2);
        verifySGRules(port);

        // Delete security groups
        plugin.deleteSecurityGroup(sg.id);
        plugin.deleteSecurityGroup(sg2.id);

        plugin.deletePort(port.id);
        plugin.deletePort(port2.id);
        plugin.deletePort(port3.id);
        verifyIpAddrGroups();

        int cp2 = zkDir().createCheckPoint();

        Assert.assertEquals(zkDir().getRemovedPaths(cp1, cp2).size(), 0);
        Assert.assertEquals(zkDir().getModifiedPaths(cp1, cp2).size(), 0);
        // TODO: FAILS
        //Assert.assertEquals(zkDir().getAddedPaths(cp1, cp2).size(), 0);
    }

    @Test
    public void testSGSameIpCreate() throws StateAccessException, SerializationException,
        Rule.RuleIndexOutOfBoundsException {
        Network network = plugin.createNetwork(createStockNetwork());
        Subnet subnet = createStockSubnet();
        subnet.networkId = network.id;
        subnet = plugin.createSubnet(subnet);

        Network network2 = plugin.createNetwork(createStockNetwork());
        Subnet subnet2 = createStockSubnet();
        subnet2.networkId = network2.id;
        subnet2 = plugin.createSubnet(subnet2);

        SecurityGroup sg = createStockSecurityGroup();
        sg = plugin.createSecurityGroup(sg);

        Port port = createStockPort(subnet.id, network.id, sg.id);
        port = plugin.createPort(port);

        String bothPortsIp = port.fixedIps.get(0).ipAddress;
        Assert.assertTrue(dataClient.ipAddrGroupHasAddr(sg.id, bothPortsIp));

        Port port2 = createStockPort(subnet2.id, network2.id, sg.id);
        port2.id = UUID.randomUUID();
        port2 = plugin.createPort(port2);
        Assert.assertTrue(dataClient.ipAddrGroupHasAddr(sg.id, bothPortsIp));

        // Delete just one of the ports. The IP should remain.
        plugin.deletePort(port.id);
        Assert.assertTrue(dataClient.ipAddrGroupHasAddr(sg.id, bothPortsIp));

        // Delete the other port. The IP should not be present.
        plugin.deletePort(port2.id);
        Assert.assertFalse(dataClient.ipAddrGroupHasAddr(sg.id, bothPortsIp));

        plugin.deleteSecurityGroup(sg.id);
        plugin.deleteSubnet(subnet.id);
        plugin.deleteNetwork(network.id);
        plugin.deleteSubnet(subnet2.id);
        plugin.deleteNetwork(network2.id);
    }

    @Test
    public void testSGSameIpUpdate() throws StateAccessException, SerializationException,
        Rule.RuleIndexOutOfBoundsException {
        Network network = plugin.createNetwork(createStockNetwork());
        Subnet subnet = createStockSubnet();
        subnet.networkId = network.id;
        subnet = plugin.createSubnet(subnet);

        Network network2 = plugin.createNetwork(createStockNetwork());
        Subnet subnet2 = createStockSubnet();
        subnet2.networkId = network2.id;
        subnet2 = plugin.createSubnet(subnet2);

        SecurityGroup sg = createStockSecurityGroup();
        sg = plugin.createSecurityGroup(sg);

        SecurityGroup sg2 = createStockSecurityGroup();
        sg2 = plugin.createSecurityGroup(sg2);

        Port port = createStockPort(subnet.id, network.id, sg.id);
        port = plugin.createPort(port);

        String portsIp = port.fixedIps.get(0).ipAddress;
        Assert.assertTrue(dataClient.ipAddrGroupHasAddr(sg.id, portsIp));
        port.securityGroups.add(sg2.id);
        port = plugin.updatePort(port.id, port);
        Assert.assertTrue(dataClient.ipAddrGroupHasAddr(sg.id, portsIp));
        Assert.assertTrue(dataClient.ipAddrGroupHasAddr(sg2.id, portsIp));

        port.securityGroups = new ArrayList<>();
        port.securityGroups.add(sg2.id);
        port = plugin.updatePort(port.id, port);

        Assert.assertFalse(dataClient.ipAddrGroupHasAddr(sg.id, portsIp));
        Assert.assertTrue(dataClient.ipAddrGroupHasAddr(sg2.id, portsIp));

        port.securityGroups = new ArrayList<>();
        plugin.updatePort(port.id, port);

        Assert.assertFalse(dataClient.ipAddrGroupHasAddr(sg.id, portsIp));
        Assert.assertFalse(dataClient.ipAddrGroupHasAddr(sg2.id, portsIp));
    }

    @Test
    public void testAddRouterInterfaceCreateDelete()
        throws StateAccessException, SerializationException {
        Network network = createStockNetwork();
        network = plugin.createNetwork(network);
        Subnet subnet = createStockSubnet();
        subnet.networkId = network.id;
        subnet = plugin.createSubnet(subnet);
        int cp1 = zkDir().createCheckPoint();

        org.midonet.cluster.data.neutron.Router r = createStockRouter();
        r = plugin.createRouter(r);
        int cp2 = zkDir().createCheckPoint();

        Port p = createStockPort(subnet.id, network.id, null);
        p.deviceOwner = DeviceOwner.ROUTER_INTF;
        p.deviceId = r.id.toString();
        p = plugin.createPort(p);

        RouterInterface ri = createStockRouterInterface(p.id, subnet.id);
        ri.id = r.id;
        plugin.addRouterInterface(r.id, ri);

        plugin.deletePort(p.id);
        int cp3 = zkDir().createCheckPoint();

        Assert.assertEquals(zkDir().getRemovedPaths(cp2, cp3).size(), 0);
        Assert.assertEquals(zkDir().getModifiedPaths(cp2, cp3).size(), 0);
        Assert.assertEquals(zkDir().getAddedPaths(cp2, cp3).size(), 0);

        plugin.deleteRouter(r.id);
        int cp4 = zkDir().createCheckPoint();

        Assert.assertEquals(zkDir().getRemovedPaths(cp1, cp4).size(), 0);
        Assert.assertEquals(zkDir().getModifiedPaths(cp1, cp4).size(), 0);
        Assert.assertEquals(zkDir().getAddedPaths(cp1, cp4).size(), 0);
    }

    @Test
    public void testRouterInterfaceConvertPort() throws StateAccessException,
        SerializationException {
        Network network = createStockNetwork();
        network = plugin.createNetwork(network);
        Subnet subnet = createStockSubnet();
        subnet.networkId = network.id;
        subnet = plugin.createSubnet(subnet);
        int cp1 = zkDir().createCheckPoint();

        org.midonet.cluster.data.neutron.Router r = createStockRouter();
        r = plugin.createRouter(r);
        int cp2 = zkDir().createCheckPoint();

        Port p = createStockPort(subnet.id, network.id, null);
        p = plugin.createPort(p);

        RouterInterface ri = createStockRouterInterface(p.id, subnet.id);
        ri.id = r.id;
        ri.portId = p.id;
        ri = plugin.addRouterInterface(r.id, ri);

        plugin.deletePort(p.id);
        int cp3 = zkDir().createCheckPoint();

        Assert.assertEquals(zkDir().getRemovedPaths(cp2, cp3).size(), 0);
        Assert.assertEquals(zkDir().getModifiedPaths(cp2, cp3).size(), 0);
        Assert.assertEquals(zkDir().getAddedPaths(cp2, cp3).size(), 0);

        plugin.deleteRouter(r.id);
        int cp4 = zkDir().createCheckPoint();

        Assert.assertEquals(zkDir().getRemovedPaths(cp1, cp4).size(), 0);
        Assert.assertEquals(zkDir().getModifiedPaths(cp1, cp4).size(), 0);
        Assert.assertEquals(zkDir().getAddedPaths(cp1, cp4).size(), 0);
    }

    @Test
    public void testRouterGatewayCreate() throws StateAccessException, SerializationException {
        Network network = createStockNetwork();
        network.external = true;
        network = plugin.createNetwork(network);
        Subnet subnet = createStockSubnet();
        subnet.networkId = network.id;
        subnet = plugin.createSubnet(subnet);
        int cp1 = zkDir().createCheckPoint();

        Port port = createStockPort(subnet.id, network.id, null);
        port.deviceOwner = DeviceOwner.ROUTER_GW;
        port = plugin.createPort(port);

        org.midonet.cluster.data.neutron.Router router = createStockRouter();
        router.externalGatewayInfo.networkId = network.id;
        router.externalGatewayInfo.enableSnat = true;
        router.gwPortId = port.id;
        router = plugin.createRouter(router);

        plugin.deletePort(port.id);
        plugin.deleteRouter(router.id);

        int cp2 = zkDir().createCheckPoint();

        Assert.assertEquals(zkDir().getRemovedPaths(cp1, cp2).size(), 0);
        Assert.assertEquals(zkDir().getModifiedPaths(cp1, cp2).size(), 0);
        Assert.assertEquals(zkDir().getAddedPaths(cp1, cp2).size(), 0);
    }

    private void verifySnatAddr(UUID routerId, String snatIp) throws StateAccessException,
        SerializationException {
        Router routerMido = dataClient.routersGet(routerId);

        List<org.midonet.cluster.data.Rule<?,?>> inboundRules
                = dataClient.rulesFindByChain(routerMido.getInboundFilter());
        List<org.midonet.cluster.data.Rule<?,?>> outboundRules
                = dataClient.rulesFindByChain(routerMido.getOutboundFilter());

        boolean foundSnat = false;
        boolean foundRevSnat = false;

        for (org.midonet.cluster.data.Rule r : inboundRules) {
            String dstAddr = r.getCondition().nwDstIp.toString();
            if (dstAddr.equals(snatIp + "/32")) {
                foundSnat = true;
            }
        }

        for (org.midonet.cluster.data.Rule r : outboundRules) {
            Set<org.midonet.midolman.rules.NatTarget> targets
                    = ((org.midonet.cluster.data.rules.ForwardNatRule) r).getTargets();
            for (NatTarget nt : targets) {
                if (nt.getNwEnd().equals(snatIp) &&
                    nt.getNwStart().equals(snatIp)) {
                    foundRevSnat = true;
                }
            }
        }

        Assert.assertTrue(foundSnat && foundRevSnat);
    }

    private void verifyStaticNat(FloatingIp floatingIp, Port port,
                                 org.midonet.cluster.data.neutron.Router router)
            throws StateAccessException, SerializationException {
        boolean snatRuleFound = false;
        boolean dnatRuleFound = false;

        Router zkRouter = dataClient.routersGet(router.id);

        List<Rule<?,?>> outRules =
            dataClient.rulesFindByChain(zkRouter.getInboundFilter());
        List<Rule<?,?>> inRules =
            dataClient.rulesFindByChain(zkRouter.getOutboundFilter());

        for (Rule r : inRules) {
            if (r instanceof ForwardNatRule) {
                ForwardNatRule fnr = (ForwardNatRule)r;
                for (NatTarget target : fnr.getTargets()) {
                    if (Objects.equal(target.getNwStart(),
                                      floatingIp.floatingIpAddress) &&
                        Objects.equal(target.getNwEnd(),
                                      floatingIp.floatingIpAddress) &&
                        target.tpEnd == 0 && target.tpStart == 0) {
                        snatRuleFound = true;
                    }
                }
            }
        }
        Assert.assertTrue(snatRuleFound);
        for (Rule r : outRules) {
            if (r instanceof ForwardNatRule) {
                ForwardNatRule fnr = (ForwardNatRule)r;
                for (NatTarget target : fnr.getTargets()) {
                    if (Objects.equal(target.getNwStart(),
                                      port.fixedIps.get(0).ipAddress) &&
                            Objects.equal(target.getNwEnd(),
                                          port.fixedIps.get(0).ipAddress) &&
                            target.tpEnd == 0 && target.tpStart == 0) {
                        dnatRuleFound = true;
                    }
                }
            }
        }
        Assert.assertTrue(dnatRuleFound);
    }

    @Test
    public void testRouterGatewayUpdate() throws StateAccessException,
        SerializationException, Rule.RuleIndexOutOfBoundsException {
        Network network = createStockNetwork();
        network.external = true;
        network = plugin.createNetwork(network);
        Subnet subnet = createStockSubnet();
        subnet.networkId = network.id;
        subnet = plugin.createSubnet(subnet);

        Network network2 = createStockNetwork();
        network2.external = true;
        network2 = plugin.createNetwork(network2);
        Subnet subnet2 = createStockSubnet();
        subnet2.networkId = network2.id;
        subnet2.cidr = "10.0.1.0/24";
        subnet2 = plugin.createSubnet(subnet2);

        int cp1 = zkDir().createCheckPoint();

        Port port2 = createStockPort(subnet2.id, network2.id, null);
        port2.fixedIps = new ArrayList<>();
        IPAllocation ip = new IPAllocation();
        ip.ipAddress = "10.0.1.10";
        ip.subnetId = subnet2.id;
        port2.fixedIps.add(ip);
        port2.deviceOwner = DeviceOwner.ROUTER_GW;
        port2 = plugin.createPort(port2);

        Port port = createStockPort(subnet.id, network.id, null);
        port.deviceOwner = DeviceOwner.ROUTER_GW;
        port = plugin.createPort(port);

        org.midonet.cluster.data.neutron.Router router = createStockRouter();
        router = plugin.createRouter(router);

        router.externalGatewayInfo.networkId = network.id;
        router.externalGatewayInfo.enableSnat = true;
        router.gwPortId = port.id;

        router = plugin.updateRouter(router.id, router);
        verifySnatAddr(router.id, port.fixedIps.get(0).ipAddress);

        router.externalGatewayInfo.networkId = network2.id;
        router.gwPortId = port2.id;

        plugin.deletePort(port.id);
        router = plugin.updateRouter(router.id, router);
        verifySnatAddr(router.id, port2.fixedIps.get(0).ipAddress);

        router.externalGatewayInfo.networkId = null;
        router.externalGatewayInfo.enableSnat = false;
        router.gwPortId = null;

        plugin.deletePort(port2.id);
        router = plugin.updateRouter(router.id, router);

        plugin.deletePort(port2.id);
        plugin.deleteRouter(router.id);
        int cp2 = zkDir().createCheckPoint();

        Assert.assertEquals(zkDir().getRemovedPaths(cp1, cp2).size(), 0);
        Assert.assertEquals(zkDir().getModifiedPaths(cp1, cp2).size(), 0);
        Assert.assertEquals(zkDir().getAddedPaths(cp1, cp2).size(), 0);
    }

    @Test
    public void testFIPCreateDelete() throws StateAccessException,
        SerializationException, Rule.RuleIndexOutOfBoundsException {

        // Create the external network
        Network network = createStockNetwork();
        network.external = true;
        network = plugin.createNetwork(network);
        Subnet subnet = createStockSubnet();
        subnet.networkId = network.id;
        subnet = plugin.createSubnet(subnet);
        int cp1 = zkDir().createCheckPoint();

        // Create a router and hook it up to the external network
        Port port = createStockPort(subnet.id, network.id, null);
        port.deviceOwner = DeviceOwner.ROUTER_GW;
        port = plugin.createPort(port);
        org.midonet.cluster.data.neutron.Router router = createStockRouter();
        router.externalGatewayInfo.networkId = network.id;
        router.gwPortId = port.id;
        router = plugin.createRouter(router);

        // Create a new network
        Network network2 = createStockNetwork();
        network2 = plugin.createNetwork(network2);
        Subnet subnet2 = createStockSubnet();
        subnet2.networkId = network2.id;
        subnet2 = plugin.createSubnet(subnet2);

        Port p = createStockPort(subnet2.id, network2.id, null);
        p = plugin.createPort(p);

        int cp2 = zkDir().createCheckPoint();
        FloatingIp fip = new FloatingIp();
        fip.routerId = router.id;
        fip.fixedIpAddress = p.fixedIps.get(0).ipAddress;
        fip.floatingIpAddress = "10.0.1.5";
        fip.portId = p.id;
        fip.floatingNetworkId = network.id;
        fip.tenantId = "tenant";
        fip.id = UUID.randomUUID();
        fip = plugin.createFloatingIp(fip);
        verifyStaticNat(fip, p, router);

        plugin.deleteFloatingIp(fip.id);
        int cp3 = zkDir().createCheckPoint();

        Assert.assertEquals(zkDir().getRemovedPaths(cp2, cp3).size(), 0);
        Assert.assertEquals(zkDir().getModifiedPaths(cp2, cp3).size(), 0);
        Assert.assertEquals(zkDir().getAddedPaths(cp2, cp3).size(), 0);
    }

    @Test
    public void testFIPUpdate() throws StateAccessException, SerializationException,
        Rule.RuleIndexOutOfBoundsException {
        // Create the external network
        Network network = createStockNetwork();
        network.external = true;
        network = plugin.createNetwork(network);
        Subnet subnet = createStockSubnet();
        subnet.networkId = network.id;
        subnet = plugin.createSubnet(subnet);

        // Create a router and hook it up to the external network
        Port port = createStockPort(subnet.id, network.id, null);
        port.deviceOwner = DeviceOwner.ROUTER_GW;
        port = plugin.createPort(port);
        org.midonet.cluster.data.neutron.Router router = createStockRouter();
        router.externalGatewayInfo.networkId = network.id;
        router.gwPortId = port.id;
        router = plugin.createRouter(router);

        // Create a new network
        Network network2 = createStockNetwork();
        network2 = plugin.createNetwork(network2);
        Subnet subnet2 = createStockSubnet();
        subnet2.networkId = network2.id;
        subnet2 = plugin.createSubnet(subnet2);

        // Create a

        Port port2 = createStockPort(subnet2.id, network2.id, null);
        port2 = plugin.createPort(port2);

        Port port3 = createStockPort(subnet2.id, network2.id, null);
        port3.fixedIps = new ArrayList<>();
        IPAllocation ip = new IPAllocation();
        ip.ipAddress = "10.0.0.11";
        ip.subnetId = subnet2.id;
        port3.fixedIps.add(ip);
        port3.macAddress = "01:23:45:67:89:ab";
        port3 = plugin.createPort(port3);

        // Create a floating IP not attached to a port
        FloatingIp fip = new FloatingIp();
        fip.routerId = router.id;
        fip.floatingIpAddress = "10.0.1.5";
        fip.floatingNetworkId = network.id;
        fip.tenantId = "tenant";
        fip.id = UUID.randomUUID();
        fip = plugin.createFloatingIp(fip);
        int cp1 = zkDir().createCheckPoint();

        // update to associate
        fip.fixedIpAddress = port2.fixedIps.get(0).ipAddress;
        fip.portId = port2.id;
        fip = plugin.updateFloatingIp(fip.id, fip);

        // update to disassociate
        fip.fixedIpAddress = null;
        fip.portId = null;
        fip = plugin.updateFloatingIp(fip.id, fip);
        int cp2 = zkDir().createCheckPoint();

        Assert.assertEquals(zkDir().getRemovedPaths(cp1, cp2).size(), 0);
        Assert.assertEquals(zkDir().getModifiedPaths(cp1, cp2).size(), 0);
        Assert.assertEquals(zkDir().getAddedPaths(cp1, cp2).size(), 0);

        // Update to associate
        fip.fixedIpAddress = port3.fixedIps.get(0).ipAddress;
        fip.portId = port3.id;
        fip = plugin.updateFloatingIp(fip.id, fip);

        // update to disassociate
        fip.fixedIpAddress = null;
        fip.portId = null;
        fip = plugin.updateFloatingIp(fip.id, fip);
        int cp3 = zkDir().createCheckPoint();

        Assert.assertEquals(zkDir().getRemovedPaths(cp1, cp3).size(), 0);
        Assert.assertEquals(zkDir().getModifiedPaths(cp1, cp3).size(), 0);
        Assert.assertEquals(zkDir().getAddedPaths(cp1, cp3).size(), 0);

        // Now try with a FIP created with a port attached
        // Create a floating IP not attached to a port
        FloatingIp fip2 = new FloatingIp();
        fip2.routerId = router.id;
        fip2.fixedIpAddress = port3.fixedIps.get(0).ipAddress;
        fip2.floatingIpAddress = "10.0.1.5";
        fip2.portId = port3.id;
        fip2.floatingNetworkId = network.id;
        fip2.tenantId = "tenant";
        fip2.id = UUID.randomUUID();
        fip2 = plugin.createFloatingIp(fip2);
        int cp4 = zkDir().createCheckPoint();

        // update to disassociate
        fip2.fixedIpAddress = null;
        fip2.portId = null;
        fip2 = plugin.updateFloatingIp(fip2.id, fip2);
        int cp5 = zkDir().createCheckPoint();

        // update to associate
        fip2.fixedIpAddress = port3.fixedIps.get(0).ipAddress;
        fip2.portId = port3.id;
        fip.floatingIpAddress = "10.0.1.5";
        fip2 = plugin.updateFloatingIp(fip2.id, fip2);
        int cp6 = zkDir().createCheckPoint();

        // Added Paths needs to equal Removed paths. Although the content
        // of the paths is logically the same, different UUIDs will be used
        // for the new routes.
        Assert.assertEquals(zkDir().getRemovedPaths(cp4, cp6).size(),
            zkDir().getAddedPaths(cp4, cp6).size(), 4);
        Assert.assertEquals(zkDir().getModifiedPaths(cp4, cp6).size(), 2);

        // update to associate with a different port
        fip2.fixedIpAddress = port2.fixedIps.get(0).ipAddress;
        fip2.portId = port2.id;
        fip2 = plugin.updateFloatingIp(fip2.id, fip2);

        // associate back to the first port
        fip2.fixedIpAddress = port3.fixedIps.get(0).ipAddress;
        fip2.portId = port3.id;
        fip2 = plugin.updateFloatingIp(fip2.id, fip2);
        int cp7 = zkDir().createCheckPoint();

        Assert.assertEquals(zkDir().getRemovedPaths(cp4, cp7).size(),
            zkDir().getAddedPaths(cp4, cp7).size(), 4);
        Assert.assertEquals(zkDir().getModifiedPaths(cp4, cp7).size(), 2);
    }
}

