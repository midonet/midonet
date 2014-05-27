/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.common.base.Objects;
import com.google.inject.Inject;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.midonet.cluster.data.Rule;
import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.rules.RuleResult;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.packets.ARP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MidoNet implementation of Neutron plugin interface.
 */
@SuppressWarnings("unused")
public class NeutronPlugin implements NetworkApi, L3Api, SecurityGroupApi {

    private final static Logger log =
            LoggerFactory.getLogger(NeutronPlugin.class);

    @Inject
    private ZkManager zkManager;

    @Inject
    private ProviderRouterZkManager providerRouterZkManager;

    @Inject
    private NetworkZkManager networkZkManager;

    @Inject
    private ExternalNetZkManager externalNetZkManager;

    @Inject
    private L3ZkManager l3ZkManager;

    @Inject
    private SecurityGroupZkManager securityGroupZkManager;

    private static void printOps(List<Op> ops) {

        if (!log.isDebugEnabled()) return;

        log.debug("******** BEGIN PRINTING ZK OPs *********");

        for (Op op : ops) {
            log.info(ZooDefs.opNames[op.getType()] + " " + op.getPath());
        }

        log.debug("******** END PRINTING ZK OPs *********");
    }

    private void commitOps(List<Op> ops) throws StateAccessException {
        if (ops.size() > 0) {
            printOps(ops);
            zkManager.multi(ops);
        }
    }

    @Override
    public Network createNetwork(@Nonnull Network network)
            throws StateAccessException, SerializationException {

        if (network.external) {
            // Ensure that the provider router is created for this provider.
            // There is no need to link this to any network until a subnet is
            // created.  But operators can now configure this router.
            // There is no need to delete these routers even when external
            // networks are deleted.
            providerRouterZkManager.ensureExists();
        }

        List<Op> ops = new ArrayList<>();
        networkZkManager.prepareCreateNetwork(ops, network);
        commitOps(ops);

        return getNetwork(network.id);
    }

    @Override
    public List<Network> createNetworkBulk(@Nonnull List<Network> networks)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        for (Network network : networks) {
            networkZkManager.prepareCreateNetwork(ops, network);
        }

        commitOps(ops);

        List<Network> nets = new ArrayList<>(networks.size());
        for (Network network : networks) {
            nets.add(getNetwork(network.id));
        }
        return nets;
    }

    @Override
    public void deleteNetwork(@Nonnull UUID id)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        Network net = networkZkManager.prepareDeleteNetwork(ops, id);

        if (net.external) {
            // For external networks, deleting the bridge is not enough.  The
            // ports on the bridge are all deleted but the peer ports on the
            // provider router are not.  Delete them here.
            externalNetZkManager.prepareDeleteDanglingProviderPorts(ops, net);
        }

        commitOps(ops);
    }

    @Override
    public Network getNetwork(@Nonnull UUID id)
            throws StateAccessException, SerializationException {
        return networkZkManager.getNetwork(id);
    }

    @Override
    public List<Network> getNetworks()
            throws StateAccessException, SerializationException {
        return networkZkManager.getNetworks();
    }

    @Override
    public Network updateNetwork(@Nonnull UUID id, @Nonnull Network network)
            throws StateAccessException, SerializationException,
            BridgeZkManager.VxLanPortIdUpdateException {

        List<Op> ops = new ArrayList<>();
        networkZkManager.prepareUpdateNetwork(ops, network);
        externalNetZkManager.prepareUpdateExternalNetwork(ops, network);

        // TODO: Include ZK version when updating
        // Throws NotStatePathException if it does not exist.
        commitOps(ops);

        return getNetwork(id);
    }

    @Override
    public Subnet createSubnet(@Nonnull Subnet subnet)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        networkZkManager.prepareCreateSubnet(ops, subnet);

        // For external network, link the bridge to the provider router.
        Network network = getNetwork(subnet.networkId);
        if (network.external) {
            externalNetZkManager.prepareLinkToProvider(ops, subnet);
        }

        commitOps(ops);

        return getSubnet(subnet.id);
    }

    @Override
    public List<Subnet> createSubnetBulk(@Nonnull List<Subnet> subnets)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        for (Subnet subnet: subnets) {
            networkZkManager.prepareCreateSubnet(ops, subnet);
        }
        commitOps(ops);

        List<Subnet> newSubnets = new ArrayList<>(subnets.size());
        for (Subnet subnet : subnets) {
            newSubnets.add(getSubnet(subnet.id));
        }

        return newSubnets;
    }

    @Override
    public void deleteSubnet(@Nonnull UUID id)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        Subnet sub = networkZkManager.prepareDeleteSubnet(ops, id);

        Network network = getNetwork(sub.networkId);
        if (network.external) {
            externalNetZkManager.prepareUnlinkFromProvider(ops, sub);
        }

        commitOps(ops);
    }

    @Override
    public Subnet getSubnet(@Nonnull UUID id)
            throws StateAccessException, SerializationException {
        return networkZkManager.getSubnet(id);
    }

    @Override
    public List<Subnet> getSubnets()
            throws StateAccessException, SerializationException {
        return networkZkManager.getSubnets();
    }

    @Override
    public Subnet updateSubnet(@Nonnull UUID id, @Nonnull Subnet subnet)
            throws StateAccessException, SerializationException {

        List<Op> ops  = new ArrayList<>();
        networkZkManager.prepareUpdateSubnet(ops, subnet);

        // This should throw NoStatePathException if it doesn't exist.
        commitOps(ops);

        return getSubnet(id);
    }

    private void createPortOps(List<Op> ops, Port port)
            throws SerializationException, StateAccessException {

        networkZkManager.prepareCreateNeutronPort(ops, port);

        if (port.isVif()) {

            PortConfig cfg = networkZkManager.prepareCreateVifPort(ops, port);

            securityGroupZkManager.preparePortSecurityGroupBindings(ops, port,
                    cfg);

            Network net = getNetwork(port.networkId);
            if (net.external) {
                externalNetZkManager.prepareCreateExtNetRoute(ops, port);
            }

        } else if (port.isDhcp()) {

            networkZkManager.prepareCreateDhcpPort(ops, port);

        } else if (port.isRouterInterface()) {

            // Create a port on the bridge but leave it unlinked.  When
            // prepareCreateRouterInterface is executed, this port is linked.
            networkZkManager.prepareCreateBridgePort(ops, port);

        } else if (port.isRouterGateway()) {

            l3ZkManager.prepareCreateProviderRouterGwPort(ops, port);

        }
    }

    @Override
    public Port createPort(@Nonnull Port port)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        createPortOps(ops, port);
        commitOps(ops);

        return getPort(port.id);
    }

    @Override
    public List<Port> createPortBulk(@Nonnull List<Port> ports)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException {

        List<Op> ops = new ArrayList<>();
        for (Port port : ports) {
            createPortOps(ops, port);
        }
        commitOps(ops);

        List<Port> outPorts = new ArrayList<>(ports.size());
        for (Port port : ports) {
            outPorts.add(getPort(port.id));
        }
        return outPorts;
    }

    @Override
    public void deletePort(@Nonnull UUID id)
            throws StateAccessException, SerializationException {

        Port port = getPort(id);
        if (port == null) {
            return;
        }

        List<Op> ops = new ArrayList<>();

        if(port.isVif()) {

            // Remove routes on the provider router if external network
            Network net = getNetwork(port.networkId);
            if (net.external) {
                externalNetZkManager.prepareDeleteExtNetRoute(ops, port);
            }

            l3ZkManager.prepareDisassociateFloatingIp(ops, port);
            securityGroupZkManager.prepareDeletePortSecurityGroup(ops, port);
            networkZkManager.prepareDeleteVifPort(ops, port);

        } else if(port.isDhcp()) {

            networkZkManager.prepareDeleteDhcpPort(ops, port);

        }  else if (port.isRouterInterface()) {

            networkZkManager.prepareDeletePortConfig(ops, port.id);

        } else if (port.isRouterGateway()) {

            l3ZkManager.prepareDeleteGatewayPort(ops, port);

        }

        networkZkManager.prepareDeleteNeutronPort(ops, port);
        commitOps(ops);
    }

    @Override
    public Port getPort(@Nonnull UUID id)
            throws StateAccessException, SerializationException {
        return networkZkManager.getPort(id);
    }

    @Override
    public List<Port> getPorts()
            throws StateAccessException, SerializationException {
        return networkZkManager.getPorts();
    }

    @Override
    public Port updatePort(@Nonnull UUID id, @Nonnull Port port)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException {

        // Fixed IP and security groups can be updated
        List<Op> ops = new ArrayList<>();

        if (port.isVif()) {

            securityGroupZkManager.prepareUpdatePortSecurityGroupBindings(
                    ops, port);
            networkZkManager.prepareUpdateVifPort(ops, port);

        } else if (port.isDhcp()) {

            networkZkManager.prepareUpdateDhcpPort(ops, port);

        }

        // Update the neutron port config
        networkZkManager.prepareUpdateNeutronPort(ops, port);

        // This should throw NoStatePathException if it doesn't exist.
        commitOps(ops);

        return getPort(id);
    }

    @Override
    public Router createRouter(@Nonnull Router router)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();

        // Create a RouterConfig in ZK
        l3ZkManager.prepareCreateRouter(ops, router);
        commitOps(ops);

        return getRouter(router.id);
    }

    @Override
    public Router getRouter(@Nonnull UUID id)
            throws StateAccessException, SerializationException {
        return l3ZkManager.getRouter(id);
    }

    @Override
    public List<Router> getRouters()
            throws StateAccessException, SerializationException {
        return l3ZkManager.getRouters();
    }

    @Override
    public void deleteRouter(@Nonnull UUID id)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        l3ZkManager.prepareDeleteRouter(ops, id);
        commitOps(ops);
    }

    @Override
    public Router updateRouter(@Nonnull UUID id, @Nonnull Router router)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException {

        List<Op> ops = new ArrayList<>();

        // Update the router config
        l3ZkManager.prepareUpdateRouter(ops, router);

        // This should throw NoPathExistsException if the resource does not
        // exist.
        commitOps(ops);

        return getRouter(router.id);
    }

    @Override
    public RouterInterface addRouterInterface(
            @Nonnull UUID routerId, @Nonnull RouterInterface routerInterface)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        l3ZkManager.prepareCreateRouterInterface(ops, routerInterface);
        commitOps(ops);

        return routerInterface;
    }

    @Override
    public RouterInterface removeRouterInterface(
            @Nonnull UUID routerId, @Nonnull RouterInterface routerInterface) {

        // Since the ports are already deleted by the time this is called,
        // there is nothing to do.
        return routerInterface;

    }

    @Override
    public FloatingIp createFloatingIp(@Nonnull FloatingIp floatingIp)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        l3ZkManager.prepareCreateFloatingIp(ops, floatingIp);
        commitOps(ops);

        return getFloatingIp(floatingIp.id);
    }

    @Override
    public FloatingIp getFloatingIp(@Nonnull UUID id)
            throws StateAccessException, SerializationException {

        return l3ZkManager.getFloatingIp(id);
    }

    @Override
    public List<FloatingIp> getFloatingIps()
            throws StateAccessException, SerializationException {

        return l3ZkManager.getFloatingIps();
    }

    @Override
    public void deleteFloatingIp(@Nonnull UUID id)
            throws StateAccessException, SerializationException {

        // Delete FIP in Neutron deletes the router interface port, which
        // calls MN's deletePort and disassociates FIP.  The only thing left
        // to do is delete the floating IP entry.
        List<Op> ops = new ArrayList<>();
        l3ZkManager.prepareDeleteFloatingIp(ops, id);
        commitOps(ops);
    }

    @Override
    public FloatingIp updateFloatingIp(@Nonnull UUID id,
                                       @Nonnull FloatingIp floatingIp)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException {

        FloatingIp oldFip = l3ZkManager.getFloatingIp(id);
        if (oldFip == null) {
            return null;
        }

        List<Op> ops = new ArrayList<>();
        l3ZkManager.prepareUpdateFloatingIp(ops, floatingIp);
        commitOps(ops);

        return l3ZkManager.getFloatingIp(id);
    }

    @Override
    public SecurityGroup createSecurityGroup(@Nonnull SecurityGroup sg)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException {

        List<Op> ops = new ArrayList<>();
        securityGroupZkManager.prepareCreateSecurityGroup(ops, sg);
        commitOps(ops);

        return getSecurityGroup(sg.id);
    }

    @Override
    public List<SecurityGroup> createSecurityGroupBulk(
            @Nonnull List<SecurityGroup> sgs)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException {
        List<Op> ops = new ArrayList<>();
        for(SecurityGroup sg : sgs) {
            securityGroupZkManager.prepareCreateSecurityGroup(ops, sg);
        }
        commitOps(ops);

        List<SecurityGroup> newSgs = new ArrayList<>(sgs.size());
        for (SecurityGroup sg : sgs) {
            newSgs.add(getSecurityGroup(sg.id));
        }

        return newSgs;
    }

    @Override
    public void deleteSecurityGroup(@Nonnull UUID id)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        securityGroupZkManager.prepareDeleteSecurityGroup(ops, id);
        commitOps(ops);
    }

    @Override
    public SecurityGroup getSecurityGroup(@Nonnull UUID id)
            throws StateAccessException, SerializationException {

        SecurityGroup sg = securityGroupZkManager.getSecurityGroup(id);
        if (sg == null) {
            return null;
        }

        // Also return security group rules.
        sg.securityGroupRules = securityGroupZkManager.getSecurityGroupRules(
                sg.id);

        return sg;
    }

    @Override
    public List<SecurityGroup> getSecurityGroups()
            throws StateAccessException, SerializationException {

        List<SecurityGroup> sgs = securityGroupZkManager.getSecurityGroups();

        // Also get their rules
        for (SecurityGroup sg : sgs) {
            sg.securityGroupRules =
                    securityGroupZkManager.getSecurityGroupRules(sg.id);
        }

        return sgs;
    }

    @Override
    public SecurityGroup updateSecurityGroup(
            @Nonnull UUID id, @Nonnull SecurityGroup sg)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        securityGroupZkManager.prepareUpdateSecurityGroup(ops, sg);

        // This should throw NoStatePathException if it doesn't exist.
        commitOps(ops);

        return getSecurityGroup(id);
    }

    @Override
    public SecurityGroupRule createSecurityGroupRule(
            @Nonnull SecurityGroupRule rule)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException {

        List<Op> ops = new ArrayList<>();
        securityGroupZkManager.prepareCreateSecurityGroupRule(ops, rule);
        commitOps(ops);

        return getSecurityGroupRule(rule.id);
    }

    @Override
    public List<SecurityGroupRule> createSecurityGroupRuleBulk(
            @Nonnull List<SecurityGroupRule> rules)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException {

        List<Op> ops = new ArrayList<>();
        for(SecurityGroupRule rule : rules) {
            securityGroupZkManager.prepareCreateSecurityGroupRule(ops, rule);
        }
        commitOps(ops);

        List<SecurityGroupRule> newRules = new ArrayList<>(rules.size());
        for(SecurityGroupRule rule : rules) {
            newRules.add(getSecurityGroupRule(rule.id));
        }

        return newRules;
    }

    @Override
    public void deleteSecurityGroupRule(@Nonnull UUID id)
            throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        securityGroupZkManager.prepareDeleteSecurityGroupRule(ops, id);
        commitOps(ops);
    }

    @Override
    public SecurityGroupRule getSecurityGroupRule(@Nonnull UUID id)
            throws StateAccessException, SerializationException {
        return securityGroupZkManager.getSecurityGroupRule(id);

    }

    @Override
    public List<SecurityGroupRule> getSecurityGroupRules()
            throws StateAccessException, SerializationException {
        return securityGroupZkManager.getSecurityGroupRules();
    }

    public ProviderRouter getProviderRouter() throws StateAccessException,
            SerializationException {
        return providerRouterZkManager.getSafe();
    }
}
