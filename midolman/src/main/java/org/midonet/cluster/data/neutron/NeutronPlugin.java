/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import com.google.inject.Inject;

import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;
import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.ZookeeperLockFactory;
import org.midonet.cluster.data.Rule;
import org.midonet.cluster.data.neutron.loadbalancer.HealthMonitor;
import org.midonet.cluster.data.neutron.loadbalancer.Member;
import org.midonet.cluster.data.neutron.loadbalancer.Pool;
import org.midonet.cluster.data.neutron.loadbalancer.PoolHealthMonitor;
import org.midonet.cluster.data.neutron.loadbalancer.VIP;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.ZkOpList;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;

/**
 * MidoNet implementation of Neutron plugin interface.
 */
@SuppressWarnings("unused")
public class NeutronPlugin implements NetworkApi, L3Api, SecurityGroupApi,
                                      LoadBalancerApi {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(NeutronPlugin.class);

    public static final String LOCK_NAME = "neutron";
    public static final int LOCK_WAIT_SEC = 5;

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
    @Inject
    private LBZkManager lbZkManager;

    @Inject
    private ZookeeperLockFactory lockFactory;

    private void commitOps(List<Op> ops) throws StateAccessException {
        ZkOpList opList = new ZkOpList(zkManager);
        opList.addAll(ops);
        opList.commit();
    }

    // The following wrapper functions for locking are defined so that
    // these lock methods throw a RuntimeException instead of checked Exception
    private InterProcessSemaphoreMutex acquireLock() {

        InterProcessSemaphoreMutex lock = lockFactory.createShared(LOCK_NAME);

        try {
            if (!lock.acquire(LOCK_WAIT_SEC, TimeUnit.SECONDS)) {
                throw new RuntimeException("Could not acquire lock in time");
            }
        } catch (Exception ex){
            throw new RuntimeException(ex);
        }

        return lock;
    }

    private void releaseLock(InterProcessSemaphoreMutex lock) {

        try {
            lock.release();
        } catch (Exception ex){
            throw new RuntimeException(ex);
        }
    }

    @Override
    public Network createNetwork(@Nonnull Network network)
        throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            if (network.external) {
                // Ensure that the provider router is created for this provider.
                // There is no need to link this to any network until a subnet
                // is created.  But operators can now configure this router.
                // There is no need to delete these routers even when external
                // networks are deleted.
                providerRouterZkManager.ensureExists();
            }

            networkZkManager.prepareCreateNetwork(ops, network);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }

        return getNetwork(network.id);
    }

    @Override
    public List<Network> createNetworkBulk(
        @Nonnull List<Network> networks)
        throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            for (Network network : networks) {
                networkZkManager.prepareCreateNetwork(ops, network);
            }
            commitOps(ops);

        } finally {
            releaseLock(lock);
        }

        List<Network> nets = new ArrayList<>(networks.size());
        for (Network network : networks) {
            nets.add(getNetwork(network.id));
        }
        return nets;
    }

    @Override
    public void deleteNetwork(@Nonnull UUID id)
        throws StateAccessException, SerializationException {

        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            Network net = networkZkManager.getNetwork(id);
            if (net == null) {
                return;
            }

            List<Op> ops = new ArrayList<>();
            networkZkManager.prepareDeleteNetwork(ops, id);

            if (net.external) {
                // For external networks, deleting the bridge is not enough.
                // The ports on the bridge are all deleted but the peer ports on
                // the provider router are not.  Delete them here.
                externalNetZkManager.prepareDeleteDanglingProviderPorts(ops,
                                                                        net);
            }

            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
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
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            networkZkManager.prepareUpdateNetwork(ops, network);
            externalNetZkManager.prepareUpdateExternalNetwork(ops, network);

            // TODO: Include ZK version when updating
            // Throws NotStatePathException if it does not exist.
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }

        return getNetwork(id);
    }

    @Override
    public Subnet createSubnet(@Nonnull Subnet subnet)
        throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            networkZkManager.prepareCreateSubnet(ops, subnet);

            // For external network, link the bridge to the provider router.
            Network network = getNetwork(subnet.networkId);
            if (network.external) {
                externalNetZkManager.prepareLinkToProvider(ops, subnet);
            }

            commitOps(ops);
        } finally {
            releaseLock(lock);
        }

        return getSubnet(subnet.id);
    }

    @Override
    public List<Subnet> createSubnetBulk(@Nonnull List<Subnet> subnets)
        throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            for (Subnet subnet : subnets) {
                networkZkManager.prepareCreateSubnet(ops, subnet);
            }
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }

        List<Subnet> newSubnets = new ArrayList<>(subnets.size());
        for (Subnet subnet : subnets) {
            newSubnets.add(getSubnet(subnet.id));
        }

        return newSubnets;
    }

    @Override
    public void deleteSubnet(@Nonnull UUID id)
        throws StateAccessException, SerializationException {

        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            Subnet sub = networkZkManager.getSubnet(id);
            if (sub == null) {
                return;
            }

            List<Op> ops = new ArrayList<>();
            networkZkManager.prepareDeleteSubnet(ops, sub);

            Network network = getNetwork(sub.networkId);
            if (network.external) {
                externalNetZkManager.prepareUnlinkFromProvider(ops, sub);
            }

            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
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

        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            networkZkManager.prepareUpdateSubnet(ops, subnet);

            // This should throw NoStatePathException if it doesn't exist.
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }

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
            l3ZkManager.prepareAddMetadataServiceRoute(ops, port);

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

        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            createPortOps(ops, port);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }

        return getPort(port.id);
    }

    @Override
    public List<Port> createPortBulk(@Nonnull List<Port> ports)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            for (Port port : ports) {
                createPortOps(ops, port);
            }
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }

        List<Port> outPorts = new ArrayList<>(ports.size());
        for (Port port : ports) {
            outPorts.add(getPort(port.id));
        }
        return outPorts;
    }

    @Override
    public void deletePort(@Nonnull UUID id)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            Port port = getPort(id);
            if (port == null) {
                return;
            }

            List<Op> ops = new ArrayList<>();
            if (port.isVif()) {

                // Remove routes on the provider router if external network
                Network net = getNetwork(port.networkId);
                if (net.external) {
                    externalNetZkManager.prepareDeleteExtNetRoute(ops, port);
                }

                l3ZkManager.prepareDisassociateFloatingIp(ops, port);
                securityGroupZkManager
                    .prepareDeletePortSecurityGroup(ops, port);
                networkZkManager.prepareDeleteVifPort(ops, port);

            } else if (port.isDhcp()) {

                networkZkManager.prepareDeleteDhcpPort(ops, port);
                l3ZkManager.prepareRemoveMetadataServiceRoute(ops, port);

            } else if (port.isRouterInterface()) {

                networkZkManager.prepareDeletePortConfig(ops, port.id);

            } else if (port.isRouterGateway()) {

                l3ZkManager.prepareDeleteGatewayPort(ops, port);

            }

            networkZkManager.prepareDeleteNeutronPort(ops, port);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
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
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
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
        } finally {
            releaseLock(lock);
        }

        return getPort(id);
    }

    @Override
    public Router createRouter(@Nonnull Router router)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            // Create a RouterConfig in ZK
            l3ZkManager.prepareCreateRouter(ops, router);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }

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
    public final void deleteRouter(@Nonnull UUID id)
        throws StateAccessException, SerializationException {

        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            Router router = l3ZkManager.getRouter(id);
            if (router == null) {
                return;
            }

            List<Op> ops = new ArrayList<>();
            l3ZkManager.prepareDeleteRouter(ops, id);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
    }

    @Override
    public Router updateRouter(@Nonnull UUID id, @Nonnull Router router)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            // Update the router config
            l3ZkManager.prepareUpdateRouter(ops, router);

            // This should throw NoPathExistsException if the resource does not
            // exist.
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }

        return getRouter(router.id);
    }

    @Override
    public RouterInterface addRouterInterface(
        @Nonnull UUID routerId, @Nonnull RouterInterface routerInterface)
        throws StateAccessException, SerializationException {

        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            l3ZkManager.prepareCreateRouterInterface(ops, routerInterface);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }

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
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            l3ZkManager.prepareCreateFloatingIp(ops, floatingIp);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }

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

        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            FloatingIp fip = l3ZkManager.getFloatingIp(id);
            if (fip == null) {
                return;
            }

            // Delete FIP in Neutron deletes the router interface port, which
            // calls MN's deletePort and disassociates FIP.  The only thing left
            // to do is delete the floating IP entry.
            List<Op> ops = new ArrayList<>();

            l3ZkManager.prepareDeleteFloatingIp(ops, fip);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
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
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            l3ZkManager.prepareUpdateFloatingIp(ops, floatingIp);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }

        return l3ZkManager.getFloatingIp(id);
    }

    @Override
    public SecurityGroup createSecurityGroup(@Nonnull SecurityGroup sg)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            securityGroupZkManager.prepareCreateSecurityGroup(ops, sg);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }

        return getSecurityGroup(sg.id);
    }

    @Override
    public List<SecurityGroup> createSecurityGroupBulk(
        @Nonnull List<SecurityGroup> sgs)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {
        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            for (SecurityGroup sg : sgs) {
                securityGroupZkManager.prepareCreateSecurityGroup(ops, sg);
            }
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }

        List<SecurityGroup> newSgs = new ArrayList<>(sgs.size());
        for (SecurityGroup sg : sgs) {
            newSgs.add(getSecurityGroup(sg.id));
        }

        return newSgs;
    }

    @Override
    public void deleteSecurityGroup(@Nonnull UUID id)
        throws StateAccessException, SerializationException {

        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            SecurityGroup sg = securityGroupZkManager.getSecurityGroup(id);
            if (sg == null) {
                return;
            }

            List<Op> ops = new ArrayList<>();

            securityGroupZkManager.prepareDeleteSecurityGroup(ops, id);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
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
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            securityGroupZkManager.prepareUpdateSecurityGroup(ops, sg);

            // This should throw NoStatePathException if it doesn't exist.
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }

        return getSecurityGroup(id);
    }

    @Override
    public SecurityGroupRule createSecurityGroupRule(
        @Nonnull SecurityGroupRule rule)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            securityGroupZkManager.prepareCreateSecurityGroupRule(ops, rule);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }

        return getSecurityGroupRule(rule.id);
    }

    @Override
    public List<SecurityGroupRule> createSecurityGroupRuleBulk(
        @Nonnull List<SecurityGroupRule> rules)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            for (SecurityGroupRule rule : rules) {
                securityGroupZkManager.prepareCreateSecurityGroupRule(ops,
                                                                      rule);
            }
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }

        List<SecurityGroupRule> newRules = new ArrayList<>(rules.size());
        for (SecurityGroupRule rule : rules) {
            newRules.add(getSecurityGroupRule(rule.id));
        }

        return newRules;
    }

    @Override
    public void deleteSecurityGroupRule(@Nonnull UUID id)
        throws StateAccessException, SerializationException {

        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            SecurityGroupRule rule =
                securityGroupZkManager.getSecurityGroupRule(id);
            if (rule == null) {
                return;
            }

            List<Op> ops = new ArrayList<>();

            securityGroupZkManager.prepareDeleteSecurityGroupRule(ops, id);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
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

    // Pools
    @Override
    public Pool getPool(UUID id)
        throws StateAccessException, SerializationException {
        return lbZkManager.getNeutronPool(id);
    }

    @Override
    public List<Pool> getPools()
        throws StateAccessException, SerializationException {
        return lbZkManager.getNeutronPools();
    }

    @Override
    public void createPool(Pool pool)
        throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            lbZkManager.prepareCreatePool(ops, pool);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
    }

    @Override
    public void updatePool(UUID id, Pool pool)
        throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            lbZkManager.prepareUpdatePool(ops, id, pool);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
    }

    @Override
    public void deletePool(UUID id)
        throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            lbZkManager.prepareDeletePool(ops, id);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
    }

    // Members
    @Override
    public Member getMember(UUID id)
        throws StateAccessException, SerializationException {
        return lbZkManager.getNeutronMember(id);
    }

    @Override
    public List<Member> getMembers()
        throws StateAccessException, SerializationException {
        return lbZkManager.getNeutronMembers();
    }

    @Override
    public void createMember(Member member)
        throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            lbZkManager.prepareCreateMember(ops, member);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
    }

    @Override
    public void updateMember(UUID id, Member member)
        throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            lbZkManager.prepareUpdateMember(ops, id, member);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
    }

    @Override
    public void deleteMember(UUID id)
        throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            lbZkManager.prepareDeleteMember(ops, id);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
    }

    // Vips    public Member getMember(UUID id)
    @Override
    public VIP getVip(UUID id)
        throws StateAccessException, SerializationException {
        return lbZkManager.getNeutronVip(id);
    }

    @Override
    public List<VIP> getVips()
        throws StateAccessException, SerializationException {
        return lbZkManager.getNeutronVips();
    }

    @Override
    public void createVip(VIP vip)
        throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            lbZkManager.prepareCreateVip(ops, vip);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
    }

    @Override
    public void updateVip(UUID id, VIP vip)
        throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            lbZkManager.prepareUpdateVip(ops, id, vip);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
    }

    @Override
    public void deleteVip(UUID id)
        throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            lbZkManager.prepareDeleteVip(ops, id);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
    }

    // Health Monitors
    @Override
    public HealthMonitor getHealthMonitor(UUID id)
        throws StateAccessException, SerializationException {
        return lbZkManager.getNeutronHealthMonitor(id);
    }

    @Override
    public List<HealthMonitor> getHealthMonitors()
        throws StateAccessException, SerializationException {
        return lbZkManager.getNeutronHealthMonitors();
    }

    @Override
    public void createHealthMonitor(HealthMonitor healthMonitor)
        throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            lbZkManager.prepareCreateHealthMonitor(ops, healthMonitor);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
    }

    @Override
    public void updateHealthMonitor(UUID id,
                                             HealthMonitor healthMonitor)
        throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            lbZkManager.prepareUpdateHealthMonitor(ops, id, healthMonitor);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
    }

    @Override
    public void deleteHealthMonitor(UUID id)
        throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            lbZkManager.prepareDeleteHealthMonitor(ops, id);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
    }

    // Pool Health Monitors
    @Override
    public void createPoolHealthMonitor(UUID poolId,
                                        PoolHealthMonitor poolHealthMonitor)
        throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            lbZkManager.createPoolHealthMonitor(ops, poolId, poolHealthMonitor);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
    }

    @Override
    public void deletePoolHealthMonitor(UUID poolId, UUID hmId)
        throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        InterProcessSemaphoreMutex lock = acquireLock();
        try {
            lbZkManager.deletePoolHealthMonitor(ops, poolId, hmId);
            commitOps(ops);
        } finally {
            releaseLock(lock);
        }
    }
}
