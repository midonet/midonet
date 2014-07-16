/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.inject.Inject;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.midonet.cluster.data.Converter;
import org.midonet.cluster.data.Rule;
import org.midonet.cluster.data.l4lb.LoadBalancer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.midolman.state.l4lb.MappingStatusException;
import org.midonet.midolman.state.l4lb.MappingViolationException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.ConfigGetter;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.cluster.data.l4lb.HealthMonitor;
import org.midonet.cluster.data.l4lb.Pool;
import org.midonet.cluster.data.l4lb.PoolMember;
import org.midonet.cluster.data.l4lb.VIP;
import org.midonet.midolman.state.zkManagers.HealthMonitorZkManager;
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager;
import org.midonet.midolman.state.zkManagers.PoolMemberZkManager;
import org.midonet.midolman.state.zkManagers.PoolZkManager;
import org.midonet.midolman.state.zkManagers.VipZkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * MidoNet implementation of Neutron plugin interface.
 */
@SuppressWarnings("unused")
public class NeutronPlugin implements NetworkApi, L3Api, SecurityGroupApi,
        LBaaSApi {

    private static final Logger LOGGER =
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

    @Inject
    private LoadBalancerZkManager loadBalancerZkManager;

    @Inject
    private HealthMonitorZkManager healthMonitorZkManager;

    @Inject
    private PoolMemberZkManager poolMemberZkManager;

    @Inject
    private RouterZkManager routerZkManager;

    @Inject
    private PoolZkManager poolZkManager;

    @Inject
    private VipZkManager vipZkManager;

    @Inject
    private PathBuilder pathBuilder;

    @Inject
    private Serializer serializer;

    private final static Logger log =
            LoggerFactory.getLogger(NeutronPlugin.class);

    private static void printOps(List<Op> ops) {

        if (!LOGGER.isDebugEnabled()) return;

        LOGGER.debug("******** BEGIN PRINTING ZK OPs *********");

        for (Op op : ops) {
            LOGGER.info(ZooDefs.opNames[op.getType()] + " " + op.getPath());
        }

        LOGGER.debug("******** END PRINTING ZK OPs *********");
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
    public List<Network> createNetworkBulk(
            @Nonnull List<Network> networks)
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
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException {

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
            l3ZkManager.prepareRemoveMetadataServiceRoute(ops, port);

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
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException {

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
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException {

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
    public FloatingIp updateFloatingIp(@Nonnull UUID id, @Nonnull FloatingIp floatingIp)
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

    /* load balancer related methods */
    @Override
    public boolean loadBalancerExists(UUID id)
            throws StateAccessException {
        return loadBalancerZkManager.exists(id);
    }

    @Override
    @CheckForNull
    public LoadBalancer loadBalancerGet(UUID id)
            throws StateAccessException, SerializationException {
        LoadBalancer loadBalancer = null;
        if (loadBalancerZkManager.exists(id)) {
            loadBalancer = Converter.fromLoadBalancerConfig(
                    loadBalancerZkManager.get(id));
            loadBalancer.setId(id);
        }

        return loadBalancer;
    }

    @Override
    public void loadBalancerDelete(UUID id)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();

        Set<UUID> poolIds = loadBalancerZkManager.getPoolIds(id);
        for (UUID poolId : poolIds) {
            ops.addAll(buildPoolDeleteOps(poolId));
        }

        LoadBalancerZkManager.LoadBalancerConfig loadBalancerConfig =
                loadBalancerZkManager.get(id);
        if (loadBalancerConfig.routerId != null) {
            ops.addAll(
                    routerZkManager.prepareClearRefsToLoadBalancer(
                            loadBalancerConfig.routerId, id));
        }

        ops.addAll(loadBalancerZkManager.prepareDelete(id));
        zkManager.multi(ops);
    }

    @Override
    public UUID loadBalancerCreate(@Nonnull LoadBalancer loadBalancer)
            throws StateAccessException, SerializationException,
            InvalidStateOperationException {
        if (loadBalancer.getId() == null) {
            loadBalancer.setId(UUID.randomUUID());
        }

        LoadBalancerZkManager.LoadBalancerConfig loadBalancerConfig =
                Converter.toLoadBalancerConfig(loadBalancer);
        loadBalancerZkManager.create(loadBalancer.getId(),
                loadBalancerConfig);

        return loadBalancer.getId();
    }

    @Override
    public void loadBalancerUpdate(@Nonnull LoadBalancer loadBalancer)
            throws StateAccessException, SerializationException,
            InvalidStateOperationException {
        LoadBalancerZkManager.LoadBalancerConfig loadBalancerConfig =
                Converter.toLoadBalancerConfig(loadBalancer);
        loadBalancerZkManager.update(loadBalancer.getId(),
                loadBalancerConfig);
    }

    @Override
    public List<LoadBalancer> loadBalancersGetAll()
            throws StateAccessException, SerializationException {
        List<LoadBalancer> loadBalancers = new ArrayList<>();

        String path = pathBuilder.getLoadBalancersPath();
        if (zkManager.exists(path)) {
            Set<String> loadBalancerIds = zkManager.getChildren(path);
            for (String id: loadBalancerIds) {
                LoadBalancer loadBalancer =
                        loadBalancerGet(UUID.fromString(id));
                if (loadBalancer != null) {
                    loadBalancers.add(loadBalancer);
                }
            }
        }

        return loadBalancers;
    }

    @Override
    public List<Pool> loadBalancerGetPools(UUID id)
            throws StateAccessException, SerializationException {
        Set<UUID> poolIds = loadBalancerZkManager.getPoolIds(id);
        List<Pool> pools = new ArrayList<>(poolIds.size());
        for (UUID poolId : poolIds) {
            Pool pool = Converter.fromPoolConfig(poolZkManager.get(poolId));
            pool.setId(poolId);
            pools.add(pool);
        }

        return pools;
    }

    @Override
    public List<VIP> loadBalancerGetVips(UUID id)
            throws StateAccessException, SerializationException {
        Set<UUID> vipIds = loadBalancerZkManager.getVipIds(id);
        List<VIP> vips = new ArrayList<>(vipIds.size());
        for (UUID vipId : vipIds) {
            VIP vip = Converter.fromVipConfig(vipZkManager.get(vipId));
            vip.setId(vipId);
            vips.add(vip);
        }
        return vips;
    }

    private void validatePoolConfigMappingStatus(UUID poolId)
            throws MappingStatusException, StateAccessException,
            SerializationException {
        PoolZkManager.PoolConfig poolConfig = poolZkManager.get(poolId);
        if (poolConfig.isImmutable()) {
            throw new MappingStatusException(
                    poolConfig.mappingStatus.toString());
        }
    }

    /*
     * Returns the pair of the mapping path and the mapping config. If the
     * given pool is not associated with any health monitor, it returns `null`.
     */
    private MutablePair<String, PoolZkManager.PoolHealthMonitorMappingConfig>
    preparePoolHealthMonitorMappings(
            @Nonnull UUID poolId,
            @Nonnull PoolZkManager.PoolConfig poolConfig,
            ConfigGetter<UUID, PoolMemberZkManager.PoolMemberConfig> poolMemberConfigGetter,
            ConfigGetter<UUID, VipZkManager.VipConfig> vipConfigGetter)
            throws MappingStatusException, SerializationException,
            StateAccessException {
        UUID healthMonitorId = poolConfig.healthMonitorId;
        // If the health monitor ID is null, the mapping should not be created
        // and therefore `null` is returned.
        if (healthMonitorId == null)
            return null;
        // If `mappingStatus` property of Pool is in PENDING_*, it throws the
        // exception and prevent the mapping from being updated.
        validatePoolConfigMappingStatus(poolId);

        String mappingPath = pathBuilder.getPoolHealthMonitorMappingsPath(
                poolId, healthMonitorId);

        assert poolConfig.loadBalancerId != null;
        UUID loadBalancerId = checkNotNull(
                poolConfig.loadBalancerId, "LoadBalancer ID is null.");
        PoolZkManager.PoolHealthMonitorMappingConfig.LoadBalancerConfigWithId loadBalancerConfig =
                new PoolZkManager.PoolHealthMonitorMappingConfig.LoadBalancerConfigWithId(
                        loadBalancerZkManager.get(loadBalancerId));

        List<UUID> memberIds = poolZkManager.getMemberIds(poolId);
        List<PoolZkManager.PoolHealthMonitorMappingConfig.PoolMemberConfigWithId> memberConfigs =
                new ArrayList<>(memberIds.size());
        for (UUID memberId : memberIds) {
            PoolMemberZkManager.PoolMemberConfig config = poolMemberConfigGetter.get(memberId);
            if (config != null) {
                config.id = memberId;
                memberConfigs.add(new PoolZkManager.PoolHealthMonitorMappingConfig.PoolMemberConfigWithId(config));
            }
        }

        List<UUID> vipIds = poolZkManager.getVipIds(poolId);
        List<PoolZkManager.PoolHealthMonitorMappingConfig.VipConfigWithId> vipConfigs = new ArrayList<>(vipIds.size());
        for (UUID vipId : vipIds) {
            VipZkManager.VipConfig config = vipConfigGetter.get(vipId);
            if (config != null) {
                config.id = vipId;
                vipConfigs.add(new PoolZkManager.PoolHealthMonitorMappingConfig.VipConfigWithId(config));
            }
        }

        PoolZkManager.PoolHealthMonitorMappingConfig.HealthMonitorConfigWithId healthMonitorConfig =
                new PoolZkManager.PoolHealthMonitorMappingConfig.HealthMonitorConfigWithId(
                        healthMonitorZkManager.get(healthMonitorId));

        PoolZkManager.PoolHealthMonitorMappingConfig mappingConfig =
                new PoolZkManager.PoolHealthMonitorMappingConfig(
                        loadBalancerConfig,
                        vipConfigs,
                        memberConfigs,
                        healthMonitorConfig);
        return new MutablePair<>(mappingPath, mappingConfig);
    }

    private List<Op> preparePoolHealthMonitorMappingUpdate(
            Pair<String, PoolZkManager.PoolHealthMonitorMappingConfig> pair)
            throws SerializationException {
        List<Op> ops = new ArrayList<>();
        if (pair != null) {
            String mappingPath = pair.getLeft();
            PoolZkManager.PoolHealthMonitorMappingConfig mappingConfig = pair.getRight();
            ops.add(Op.setData(mappingPath,
                    serializer.serialize(mappingConfig), -1));
        }
        return ops;
    }

    /* health monitors related methods */
    private List<Pair<String, PoolZkManager.PoolHealthMonitorMappingConfig>>
    buildPoolHealthMonitorMappings(UUID healthMonitorId,
                                   @Nullable HealthMonitorZkManager.HealthMonitorConfig config)
            throws MappingStatusException, SerializationException,
            StateAccessException {
        List<UUID> poolIds =
                healthMonitorZkManager.getPoolIds(healthMonitorId);
        List<Pair<String, PoolZkManager.PoolHealthMonitorMappingConfig>> pairs =
                new ArrayList<>();

        for (UUID poolId: poolIds) {
            PoolZkManager.PoolConfig poolConfig = poolZkManager.get(poolId);
            MutablePair<String, PoolZkManager.PoolHealthMonitorMappingConfig> pair =
                    preparePoolHealthMonitorMappings(poolId,
                            poolConfig, poolMemberZkManager, vipZkManager);

            if (pair != null) {
                // Update health monitor config, which can be null
                PoolZkManager.PoolHealthMonitorMappingConfig updatedMappingConfig =
                        pair.getRight();
                updatedMappingConfig.healthMonitorConfig =
                        new PoolZkManager.PoolHealthMonitorMappingConfig.HealthMonitorConfigWithId(config);
                pair.setRight(updatedMappingConfig);
                pairs.add(pair);
            }
        }
        return pairs;
    }

    @Override
    public boolean healthMonitorExists(UUID id)
            throws StateAccessException {
        return healthMonitorZkManager.exists(id);
    }

    @Override
    @CheckForNull
    public HealthMonitor healthMonitorGet(UUID id)
            throws StateAccessException, SerializationException {
        HealthMonitor healthMonitor = null;
        if (healthMonitorZkManager.exists(id)) {
            healthMonitor = Converter.fromHealthMonitorConfig(
                    healthMonitorZkManager.get(id));
            healthMonitor.setId(id);
        }

        return healthMonitor;
    }

    @Override
    public void healthMonitorDelete(UUID id)
            throws MappingStatusException, StateAccessException,
            SerializationException {
        List<Op> ops = new ArrayList<>();

        List<UUID> poolIds = healthMonitorZkManager.getPoolIds(id);
        for (UUID poolId : poolIds) {
            validatePoolConfigMappingStatus(poolId);

            PoolZkManager.PoolConfig poolConfig = poolZkManager.get(poolId);
            ops.add(Op.setData(pathBuilder.getPoolPath(poolId),
                    serializer.serialize(poolConfig), -1));
            // Pool-HealthMonitor mappings
            ops.add(Op.delete(pathBuilder.getPoolHealthMonitorMappingsPath(
                    poolId, id), -1));
            poolConfig.healthMonitorId = null;
            // Indicate the mapping is being deleted.
            poolConfig.mappingStatus =
                    PoolHealthMonitorMappingStatus.PENDING_DELETE;
            ops.addAll(poolZkManager.prepareUpdate(poolId, poolConfig));
            ops.addAll(healthMonitorZkManager.prepareRemovePool(id, poolId));
        }

        ops.addAll(healthMonitorZkManager.prepareDelete(id));
        zkManager.multi(ops);
    }

    @Override
    public UUID healthMonitorCreate(@Nonnull HealthMonitor healthMonitor)
            throws StateAccessException, SerializationException {
        if (healthMonitor.getId() == null) {
            healthMonitor.setId(UUID.randomUUID());
        }

        HealthMonitorZkManager.HealthMonitorConfig config =
                Converter.toHealthMonitorConfig(healthMonitor);

        zkManager.multi(
                healthMonitorZkManager.prepareCreate(
                        healthMonitor.getId(), config));

        return healthMonitor.getId();
    }

    @Override
    public void healthMonitorUpdate(@Nonnull HealthMonitor healthMonitor)
            throws MappingStatusException, StateAccessException,
            SerializationException {
        HealthMonitorZkManager.HealthMonitorConfig newConfig =
                Converter.toHealthMonitorConfig(healthMonitor);
        HealthMonitorZkManager.HealthMonitorConfig oldConfig =
                healthMonitorZkManager.get(healthMonitor.getId());
        UUID id = healthMonitor.getId();
        if (newConfig.equals(oldConfig))
            return;
        List<Op> ops = new ArrayList<>();
        ops.addAll(healthMonitorZkManager.prepareUpdate(id, newConfig));

        // Pool-HealthMonitor mappings
        for (Pair<String, PoolZkManager.PoolHealthMonitorMappingConfig> pair :
                buildPoolHealthMonitorMappings(id, newConfig)) {
            List<UUID> poolIds = healthMonitorZkManager.getPoolIds(id);
            for (UUID poolId : poolIds) {
                validatePoolConfigMappingStatus(poolId);

                PoolZkManager.PoolConfig poolConfig = poolZkManager.get(poolId);
                // Indicate the mapping is being updated.
                poolConfig.mappingStatus =
                        PoolHealthMonitorMappingStatus.PENDING_UPDATE;
                ops.add(Op.setData(pathBuilder.getPoolPath(poolId),
                        serializer.serialize(poolConfig), -1));
            }
            ops.addAll(preparePoolHealthMonitorMappingUpdate(pair));
        }
        zkManager.multi(ops);
    }

    @Override
    public List<HealthMonitor> healthMonitorsGetAll()
            throws StateAccessException, SerializationException {
        List<HealthMonitor> healthMonitors = new ArrayList<>();

        String path = pathBuilder.getHealthMonitorsPath();
        if (zkManager.exists(path)) {
            Set<String> healthMonitorIds = zkManager.getChildren(path);
            for (String id : healthMonitorIds) {
                HealthMonitor healthMonitor
                        = healthMonitorGet(UUID.fromString(id));
                if (healthMonitor != null) {
                    healthMonitors.add(healthMonitor);
                }
            }
        }

        return healthMonitors;
    }

    @Override
    public List<Pool> healthMonitorGetPools(@Nonnull UUID id)
            throws StateAccessException, SerializationException {
        List<UUID> poolIds = healthMonitorZkManager.getPoolIds(id);
        List<Pool> pools = new ArrayList<>(poolIds.size());
        for (UUID poolId : poolIds) {
            Pool pool = Converter.fromPoolConfig(poolZkManager.get(poolId));
            pool.setId(poolId);
            pools.add(pool);
        }
        return pools;
    }

    /* pool member related methods */
    private Pair<String, PoolZkManager.PoolHealthMonitorMappingConfig>
    buildPoolHealthMonitorMappings(final UUID poolMemberId,
                                   final @Nonnull PoolMemberZkManager.PoolMemberConfig config,
                                   final boolean deletePoolMember)
            throws MappingStatusException, SerializationException,
            StateAccessException {
        UUID poolId = checkNotNull(config.poolId, "Pool ID is null.");
        PoolZkManager.PoolConfig poolConfig = poolZkManager.get(poolId);

        // Since we haven't deleted/updated this PoolMember in Zookeeper yet,
        // preparePoolHealthMonitorMappings() will get an outdated version of
        // this PoolMember when it fetches the Pool's members. The ConfigGetter
        // intercepts the request for this PoolMember and returns the updated
        // PoolMemberConfig, or null if it's been deleted.
        ConfigGetter<UUID, PoolMemberZkManager.PoolMemberConfig> configGetter =
                new ConfigGetter<UUID, PoolMemberZkManager.PoolMemberConfig>() {
                    @Override
                    public PoolMemberZkManager.PoolMemberConfig get(UUID key)
                            throws StateAccessException, SerializationException {
                        if (key.equals(poolMemberId)) {
                            return deletePoolMember ? null : config;
                        }
                        return poolMemberZkManager.get(key);
                    }
                };

        return preparePoolHealthMonitorMappings(
                poolId, poolConfig, configGetter, vipZkManager);
    }

    private List<Op> buildPoolMappingStatusUpdate(
            PoolMemberZkManager.PoolMemberConfig poolMemberConfig)
            throws StateAccessException, SerializationException {
        UUID poolId = poolMemberConfig.poolId;
        PoolZkManager.PoolConfig poolConfig = poolZkManager.get(poolId);
        // Indicate the mapping is being updated.
        poolConfig.mappingStatus =
                PoolHealthMonitorMappingStatus.PENDING_UPDATE;
        return Arrays.asList(Op.setData(pathBuilder.getPoolPath(poolId),
                serializer.serialize(poolConfig), -1));
    }

    @Override
    @CheckForNull
    public boolean poolMemberExists(UUID id)
            throws StateAccessException {
        return poolMemberZkManager.exists(id);
    }

    @Override
    @CheckForNull
    public PoolMember poolMemberGet(UUID id)
            throws StateAccessException, SerializationException {
        PoolMember poolMember = null;
        if (poolMemberZkManager.exists(id)) {
            poolMember = Converter.fromPoolMemberConfig(poolMemberZkManager.get(id));
            poolMember.setId(id);
        }

        return poolMember;
    }

    @Override
    public void poolMemberDelete(UUID id)
            throws MappingStatusException, StateAccessException,
            SerializationException {
        List<Op> ops = new ArrayList<>();

        PoolMemberZkManager.PoolMemberConfig config = poolMemberZkManager.get(id);
        if (config.poolId != null) {
            ops.addAll(poolZkManager.prepareRemoveMember(config.poolId, id));
        }

        ops.addAll(poolMemberZkManager.prepareDelete(id));

        // Pool-HealthMonitor mappings
        Pair<String, PoolZkManager.PoolHealthMonitorMappingConfig> pair =
                buildPoolHealthMonitorMappings(id, config, true);
        ops.addAll(preparePoolHealthMonitorMappingUpdate(pair));

        if (pair != null) {
            ops.addAll(buildPoolMappingStatusUpdate(config));
        }
        zkManager.multi(ops);
    }

    @Override
    public UUID poolMemberCreate(@Nonnull PoolMember poolMember)
            throws MappingStatusException, StateAccessException,
            SerializationException {
        validatePoolConfigMappingStatus(poolMember.getPoolId());

        if (poolMember.getId() == null)
            poolMember.setId(UUID.randomUUID());
        UUID id = poolMember.getId();

        PoolMemberZkManager.PoolMemberConfig config = Converter.toPoolMemberConfig(poolMember);

        List<Op> ops = new ArrayList<>();
        ops.addAll(poolMemberZkManager.prepareCreate(id, config));
        ops.addAll(poolZkManager.prepareAddMember(config.poolId, id));

        // Flush the pool member create ops first
        zkManager.multi(ops);
        ops.clear();

        Pair<String, PoolZkManager.PoolHealthMonitorMappingConfig> pair =
                buildPoolHealthMonitorMappings(id, config, false);
        ops.addAll(preparePoolHealthMonitorMappingUpdate(pair));

        if (pair != null) {
            ops.addAll(buildPoolMappingStatusUpdate(config));
        }

        zkManager.multi(ops);
        return id;
    }

    @Override
    public void poolMemberUpdate(@Nonnull PoolMember poolMember)
            throws MappingStatusException, StateAccessException,
            SerializationException {
        validatePoolConfigMappingStatus(poolMember.getPoolId());

        UUID id = poolMember.getId();
        PoolMemberZkManager.PoolMemberConfig newConfig = Converter.toPoolMemberConfig(poolMember);
        PoolMemberZkManager.PoolMemberConfig oldConfig = poolMemberZkManager.get(id);
        boolean isPoolIdChanged =
                !com.google.common.base.Objects.equal(newConfig.poolId, oldConfig.poolId);
        if (newConfig.equals(oldConfig))
            return;

        List<Op> ops = new ArrayList<>();
        if (isPoolIdChanged) {
            ops.addAll(poolZkManager.prepareRemoveMember(
                    oldConfig.poolId, id));
            ops.addAll(poolZkManager.prepareAddMember(
                    newConfig.poolId, id));
        }

        ops.addAll(poolMemberZkManager.prepareUpdate(id, newConfig));
        // Flush the update of the pool members first
        zkManager.multi(ops);

        ops.clear();
        if (isPoolIdChanged) {
            // Remove pool member from its old owner's mapping node.
            Pair<String, PoolZkManager.PoolHealthMonitorMappingConfig> oldPair =
                    buildPoolHealthMonitorMappings(id, oldConfig, true);
            ops.addAll(preparePoolHealthMonitorMappingUpdate(oldPair));
        }

        // Update the pool's pool-HM mapping with the new member.
        Pair<String, PoolZkManager.PoolHealthMonitorMappingConfig> pair =
                buildPoolHealthMonitorMappings(id, newConfig, false);
        ops.addAll(preparePoolHealthMonitorMappingUpdate(pair));

        if (pair != null) {
            ops.addAll(buildPoolMappingStatusUpdate(newConfig));
        }
        zkManager.multi(ops);
    }

    @Override
    public void poolMemberUpdateStatus(UUID poolMemberId, LBStatus status)
            throws StateAccessException, SerializationException {
        PoolMemberZkManager.PoolMemberConfig config = poolMemberZkManager.get(poolMemberId);
        if (config == null) {
            log.error("pool member does not exist" + poolMemberId.toString());
            return;
        }
        config.status = status;
        List<Op> ops = poolMemberZkManager.prepareUpdate(poolMemberId, config);
        zkManager.multi(ops);
    }

    @Override
    public List<PoolMember> poolMembersGetAll() throws StateAccessException,
            SerializationException {
        List<PoolMember> poolMembers = new ArrayList<>();

        String path = pathBuilder.getPoolMembersPath();
        if (zkManager.exists(path)) {
            Set<String> poolMemberIds = zkManager.getChildren(path);
            for (String id : poolMemberIds) {
                PoolMember poolMember = poolMemberGet(UUID.fromString(id));
                if (poolMember != null) {
                    poolMembers.add(poolMember);
                }
            }
        }

        return poolMembers;
    }


    /* pool related methods */
    private List<Op> prepareDeletePoolHealthMonitorMappingOps(UUID poolId)
            throws SerializationException, StateAccessException {
        PoolZkManager.PoolConfig poolConfig = poolZkManager.get(poolId);
        List<Op> ops = new ArrayList<>();
        if (poolConfig.healthMonitorId != null) {
            ops.add(Op.delete(pathBuilder.getPoolHealthMonitorMappingsPath(
                    poolId, poolConfig.healthMonitorId), -1));
        }
        return ops;
    }

    @Override
    @CheckForNull
    public boolean poolExists(UUID id)
            throws StateAccessException {
        return poolZkManager.exists(id);
    }

    @Override
    @CheckForNull
    public Pool poolGet(UUID id)
            throws StateAccessException, SerializationException {
        Pool pool = null;
        if (poolZkManager.exists(id)) {
            pool = Converter.fromPoolConfig(poolZkManager.get(id));
            pool.setId(id);
        }

        return pool;
    }

    private List<Op> buildPoolDeleteOps(UUID id)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();
        PoolZkManager.PoolConfig config = poolZkManager.get(id);

        List<UUID> memberIds = poolZkManager.getMemberIds(id);
        for (UUID memberId : memberIds) {
            ops.addAll(poolZkManager.prepareRemoveMember(id, memberId));
            ops.addAll(poolMemberZkManager.prepareDelete(memberId));
        }

        List<UUID> vipIds = poolZkManager.getVipIds(id);
        for (UUID vipId : vipIds) {
            ops.addAll(poolZkManager.prepareRemoveVip(id, vipId));
            ops.addAll(vipZkManager.prepareDelete(vipId));
            ops.addAll(loadBalancerZkManager.prepareRemoveVip(
                    config.loadBalancerId, vipId));
        }

        if (config.healthMonitorId != null) {
            ops.addAll(healthMonitorZkManager.prepareRemovePool(
                    config.healthMonitorId, id));
        }
        ops.addAll(loadBalancerZkManager.prepareRemovePool(
                config.loadBalancerId, id));
        ops.addAll(poolZkManager.prepareDelete(id));
        return ops;
    }

    @Override
    public void poolDelete(UUID id)
            throws MappingStatusException, StateAccessException,
            SerializationException {
        List<Op> ops = buildPoolDeleteOps(id);
        // Pool-HealthMonitor mappings
        PoolZkManager.PoolConfig poolConfig = poolZkManager.get(id);
        validatePoolConfigMappingStatus(id);

        if (poolConfig.healthMonitorId != null) {
            ops.add(Op.delete(pathBuilder.getPoolHealthMonitorMappingsPath(
                    id, poolConfig.healthMonitorId), -1));
        }
        zkManager.multi(ops);
    }

    @Override
    public UUID poolCreate(@Nonnull Pool pool)
            throws MappingStatusException, StateAccessException,
            SerializationException {
        if (pool.getId() == null) {
            pool.setId(UUID.randomUUID());
        }
        UUID id = pool.getId();

        PoolZkManager.PoolConfig config = Converter.toPoolConfig(pool);

        List<Op> ops = new ArrayList<>();
        ops.addAll(poolZkManager.prepareCreate(id, config));

        if (config.loadBalancerId != null) {
            ops.addAll(loadBalancerZkManager.prepareAddPool(
                    config.loadBalancerId, id));
        }

        if (config.healthMonitorId != null) {
            ops.addAll(healthMonitorZkManager.prepareAddPool(
                    config.healthMonitorId, id));
        }
        // Flush the pool create ops first.
        zkManager.multi(ops);

        ops.clear();
        // Pool-HealthMonitor mappings
        Pair<String, PoolZkManager.PoolHealthMonitorMappingConfig> pair =
                preparePoolHealthMonitorMappings(
                        id, config, poolMemberZkManager, vipZkManager);
        if (pair != null) {
            String mappingPath = pair.getLeft();
            PoolZkManager.PoolHealthMonitorMappingConfig mappingConfig = pair.getRight();
            ops.add(Op.create(mappingPath,
                    serializer.serialize(mappingConfig),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
            config.mappingStatus =
                    PoolHealthMonitorMappingStatus.PENDING_CREATE;
            ops.addAll(poolZkManager.prepareUpdate(id, config));
        }
        zkManager.multi(ops);
        return id;
    }

    @Override
    public void poolUpdate(@Nonnull Pool pool)
            throws MappingStatusException, MappingViolationException,
            SerializationException, StateAccessException {
        UUID id = pool.getId();
        PoolZkManager.PoolConfig newConfig = Converter.toPoolConfig(pool);
        PoolZkManager.PoolConfig oldConfig = poolZkManager.get(id);
        boolean isHealthMonitorChanged =
                !com.google.common.base.Objects.equal(newConfig.healthMonitorId,
                        oldConfig.healthMonitorId);
        if (newConfig.equals(oldConfig))
            return;

        // Set the internal status for the Pool-HealthMonitor mapping with the
        // previous value.
        newConfig.mappingStatus = oldConfig.mappingStatus;

        List<Op> ops = new ArrayList<>();
        if (isHealthMonitorChanged) {
            if (oldConfig.healthMonitorId != null) {
                ops.addAll(healthMonitorZkManager.prepareRemovePool(
                        oldConfig.healthMonitorId, id));
            }
            if (newConfig.healthMonitorId != null) {
                ops.addAll(healthMonitorZkManager.prepareAddPool(
                        newConfig.healthMonitorId, id));
            }
        }

        if (!com.google.common.base.Objects.equal(oldConfig.loadBalancerId,
                newConfig.loadBalancerId)) {
            // Move the pool from the previous load balancer to the new one.
            ops.addAll(loadBalancerZkManager.prepareRemovePool(
                    oldConfig.loadBalancerId, id));
            ops.addAll(loadBalancerZkManager.prepareAddPool(
                    newConfig.loadBalancerId, id));
            // Move the VIPs belong to the pool from the previous load balancer
            // to the new one.
            List<UUID> vipIds = poolZkManager.getVipIds(id);
            for (UUID vipId : vipIds) {
                ops.addAll(loadBalancerZkManager.prepareRemoveVip(
                        oldConfig.loadBalancerId, vipId));
                ops.addAll(loadBalancerZkManager.prepareAddVip(
                        newConfig.loadBalancerId, vipId));
                VipZkManager.VipConfig vipConfig = vipZkManager.get(vipId);
                // Update the load balancer ID of the VIPs with the pool's one.
                vipConfig.loadBalancerId = newConfig.loadBalancerId;
                ops.addAll(vipZkManager.prepareUpdate(vipId, vipConfig));
            }
        }

        // Pool-HealthMonitor mappings
        // If the reference to the health monitor is changed, the previous
        // entries are deleted and the new entries are created.
        if (isHealthMonitorChanged) {
            PoolZkManager.PoolConfig poolConfig = poolZkManager.get(id);
            // Indicate the mapping is being deleted and once it's confirmed
            // by the health monitor, it's replaced with INACTIVE.
            if (pool.getHealthMonitorId() == null) {
                validatePoolConfigMappingStatus(id);
                newConfig.mappingStatus =
                        PoolHealthMonitorMappingStatus.PENDING_DELETE;
                // Delete the old entry
                ops.add(Op.delete(pathBuilder.getPoolHealthMonitorMappingsPath(
                        id, poolConfig.healthMonitorId), -1));
            }
            // Throws an exception if users try to update the health monitor ID
            // with another one even if the pool is already associated with
            // another health monitor.
            if (poolConfig.healthMonitorId != null
                    && pool.getHealthMonitorId() != null) {
                throw new MappingViolationException();
            }
            Pair<String, PoolZkManager.PoolHealthMonitorMappingConfig> pair =
                    preparePoolHealthMonitorMappings(
                            id, newConfig, poolMemberZkManager, vipZkManager);
            if (pair != null) {
                String mappingPath = pair.getLeft();
                PoolZkManager.PoolHealthMonitorMappingConfig mappingConfig = pair.getRight();
                ops.add(Op.create(mappingPath,
                        serializer.serialize(mappingConfig),
                        ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
                // Indicate the update is being processed and once it's confirmed
                // by the health monitor, it's replaced with ACTIVE.
                if (com.google.common.base.Objects.equal(oldConfig.mappingStatus,
                        PoolHealthMonitorMappingStatus.INACTIVE)) {
                    newConfig.mappingStatus =
                            PoolHealthMonitorMappingStatus.PENDING_CREATE;
                } else {
                    newConfig.mappingStatus =
                            PoolHealthMonitorMappingStatus.PENDING_UPDATE;
                }
            }
        }
        ops.addAll(poolZkManager.prepareUpdate(id, newConfig));
        zkManager.multi(ops);
    }

    @Override
    public List<Pool> poolsGetAll() throws StateAccessException,
            SerializationException {
        List<Pool> pools = new ArrayList<>();

        String path = pathBuilder.getPoolsPath();
        if (zkManager.exists(path)) {
            Set<String> poolIds = zkManager.getChildren(path);
            for (String id : poolIds) {
                Pool pool = poolGet(UUID.fromString(id));
                if (pool != null) {
                    pools.add(pool);
                }
            }
        }

        return pools;
    }

    @Override
    public List<PoolMember> poolGetMembers(@Nonnull UUID id)
            throws StateAccessException, SerializationException {
        List<UUID> memberIds = poolZkManager.getMemberIds(id);
        List<PoolMember> members = new ArrayList<>(memberIds.size());
        for (UUID memberId : memberIds) {
            PoolMember member = Converter.fromPoolMemberConfig(
                    poolMemberZkManager.get(memberId));
            member.setId(memberId);
            members.add(member);
        }
        return members;
    }

    @Override
    public List<VIP> poolGetVips(@Nonnull UUID id)
            throws StateAccessException, SerializationException {
        List<UUID> vipIds = poolZkManager.getVipIds(id);
        List<VIP> vips = new ArrayList<>(vipIds.size());
        for (UUID vipId : vipIds) {
            VIP vip = Converter.fromVipConfig(vipZkManager.get(vipId));
            vip.setId(vipId);
            vips.add(vip);
        }
        return vips;
    }

    @Override
    public void poolSetMapStatus(UUID id,
                                 PoolHealthMonitorMappingStatus status)
            throws StateAccessException, SerializationException{
        PoolZkManager.PoolConfig pool = poolZkManager.get(id);
        if (pool == null)
            return;
        pool.mappingStatus = status;
        zkManager.multi(poolZkManager.prepareUpdate(id, pool));
    }

    private Pair<String, PoolZkManager.PoolHealthMonitorMappingConfig>
    buildPoolHealthMonitorMappings(final UUID vipId,
                                   final @Nonnull VipZkManager.VipConfig config,
                                   final boolean deleteVip)
            throws MappingStatusException, SerializationException,
            StateAccessException {
        UUID poolId = checkNotNull(config.poolId, "Pool ID is null.");
        PoolZkManager.PoolConfig poolConfig = poolZkManager.get(poolId);

        // Since we haven't deleted/updated this VIP in Zookeeper yet,
        // preparePoolHealthMonitorMappings() will get an outdated version of
        // this VIP when it fetches the Pool's vips. The ConfigGetter
        // intercepts the request for this VIP and returns the updated
        // VipConfig, or null if it's been deleted.
        ConfigGetter<UUID, VipZkManager.VipConfig> configGetter =
                new ConfigGetter<UUID, VipZkManager.VipConfig>() {
                    @Override
                    public VipZkManager.VipConfig get(UUID key)
                            throws StateAccessException, SerializationException {
                        if (key.equals(vipId)) {
                            return deleteVip ? null : config;
                        }
                        return vipZkManager.get(key);
                    }
                };

        return preparePoolHealthMonitorMappings(
                poolId, poolConfig, poolMemberZkManager, configGetter);
    }

    private List<Op> buildPoolMappingStatusUpdate(VipZkManager.VipConfig vipConfig)
            throws StateAccessException, SerializationException {
        UUID poolId = vipConfig.poolId;
        PoolZkManager.PoolConfig poolConfig = poolZkManager.get(poolId);
        // Indicate the mapping is being updated.
        poolConfig.mappingStatus =
                PoolHealthMonitorMappingStatus.PENDING_UPDATE;
        return Arrays.asList(Op.setData(pathBuilder.getPoolPath(poolId),
                serializer.serialize(poolConfig), -1));
    }

    @Override
    @CheckForNull
    public boolean vipExists(UUID id) throws StateAccessException {
        return vipZkManager.exists(id);
    }

    @Override
    @CheckForNull
    public VIP vipGet(UUID id)
            throws StateAccessException, SerializationException {
        VIP vip = null;
        if (vipZkManager.exists(id)) {
            vip = Converter.fromVipConfig(vipZkManager.get(id));
            vip.setId(id);
        }
        return vip;
    }

    @Override
    public void vipDelete(UUID id)
            throws MappingStatusException, StateAccessException,
            SerializationException {
        List<Op> ops = new ArrayList<>();
        VipZkManager.VipConfig config = vipZkManager.get(id);

        if (config.loadBalancerId != null) {
            ops.addAll(loadBalancerZkManager.prepareRemoveVip(
                    config.loadBalancerId, id));
        }

        if (config.poolId != null) {
            ops.addAll(poolZkManager.prepareRemoveVip(config.poolId, id));
        }

        ops.addAll(vipZkManager.prepareDelete(id));

        // Pool-HealthMonitor mappings
        Pair<String, PoolZkManager.PoolHealthMonitorMappingConfig> pair =
                buildPoolHealthMonitorMappings(id, config, true);
        ops.addAll(preparePoolHealthMonitorMappingUpdate(pair));

        if (pair != null) {
            ops.addAll(buildPoolMappingStatusUpdate(config));
        }
        zkManager.multi(ops);
    }

    @Override
    public UUID vipCreate(@Nonnull VIP vip)
            throws MappingStatusException, StateAccessException,
            SerializationException {
        validatePoolConfigMappingStatus(vip.getPoolId());

        if (vip.getId() == null) {
            vip.setId(UUID.randomUUID());
        }
        UUID id = vip.getId();

        // Get the VipConfig and set its loadBalancerId from the PoolConfig.
        VipZkManager.VipConfig config = Converter.toVipConfig(vip);
        PoolZkManager.PoolConfig poolConfig = poolZkManager.get(config.poolId);
        config.loadBalancerId = poolConfig.loadBalancerId;

        List<Op> ops = new ArrayList<>();
        ops.addAll(vipZkManager.prepareCreate(id, config));
        ops.addAll(poolZkManager.prepareAddVip(config.poolId, id));
        ops.addAll(loadBalancerZkManager.prepareAddVip(
                config.loadBalancerId, id));

        // Flush the VIP first.
        zkManager.multi(ops);
        ops.clear();

        // Pool-HealthMonitor mappings
        Pair<String, PoolZkManager.PoolHealthMonitorMappingConfig> pair =
                buildPoolHealthMonitorMappings(id, config, false);
        ops.addAll(preparePoolHealthMonitorMappingUpdate(pair));

        if (pair != null) {
            ops.addAll(buildPoolMappingStatusUpdate(config));
        }
        zkManager.multi(ops);
        return id;
    }

    @Override
    public void vipUpdate(@Nonnull VIP vip)
            throws MappingStatusException, StateAccessException,
            SerializationException {
        validatePoolConfigMappingStatus(vip.getPoolId());

        // See if new config is different from old config.
        UUID id = vip.getId();
        VipZkManager.VipConfig newConfig = Converter.toVipConfig(vip);
        VipZkManager.VipConfig oldConfig = vipZkManager.get(id);

        // User can't set loadBalancerId directly because we get it from
        // from the pool indicated by poolId.
        newConfig.loadBalancerId = oldConfig.loadBalancerId;
        if (newConfig.equals(oldConfig))
            return;

        List<Op> ops = new ArrayList<>();

        boolean poolIdChanged =
                !com.google.common.base.Objects.equal(newConfig.poolId, oldConfig.poolId);
        if (poolIdChanged) {
            ops.addAll(poolZkManager.prepareRemoveVip(oldConfig.poolId, id));
            ops.addAll(loadBalancerZkManager.prepareRemoveVip(
                    oldConfig.loadBalancerId, id));

            PoolZkManager.PoolConfig newPoolConfig = poolZkManager.get(newConfig.poolId);
            newConfig.loadBalancerId = newPoolConfig.loadBalancerId;
            ops.addAll(poolZkManager.prepareAddVip(newConfig.poolId, id));
            ops.addAll(loadBalancerZkManager.prepareAddVip(
                    newPoolConfig.loadBalancerId, id));
        }

        ops.addAll(vipZkManager.prepareUpdate(id, newConfig));
        zkManager.multi(ops);

        ops.clear();
        if (poolIdChanged) {
            // Remove the VIP from the old pool-HM mapping.
            Pair<String, PoolZkManager.PoolHealthMonitorMappingConfig> oldPair =
                    buildPoolHealthMonitorMappings(id, oldConfig, true);
            ops.addAll(preparePoolHealthMonitorMappingUpdate(oldPair));
        }

        // Update the appropriate pool-HM mapping with the new VIP config.
        Pair<String, PoolZkManager.PoolHealthMonitorMappingConfig> pair =
                buildPoolHealthMonitorMappings(id, newConfig, false);
        ops.addAll(preparePoolHealthMonitorMappingUpdate(pair));

        if (pair != null) {
            ops.addAll(buildPoolMappingStatusUpdate(newConfig));
        }
        zkManager.multi(ops);
    }

    @Override
    public List<VIP> vipGetAll()
            throws StateAccessException, SerializationException {
        List<VIP> vips = new ArrayList<>();

        String path = pathBuilder.getVipsPath();
        if (zkManager.exists(path)) {
            Set<String> vipIds = zkManager.getChildren(path);
            for (String id: vipIds) {
                VIP vip = vipGet(UUID.fromString(id));
                if (vip != null) {
                    vips.add(vip);
                }
            }
        }

        return vips;
    }
}
