/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.cluster.data.neutron;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;
import javax.ws.rs.WebApplicationException;

import com.google.inject.Inject;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.ZookeeperLockFactory;
import org.midonet.cluster.data.neutron.loadbalancer.HealthMonitor;
import org.midonet.cluster.data.neutron.loadbalancer.Member;
import org.midonet.cluster.data.neutron.loadbalancer.Pool;
import org.midonet.cluster.data.neutron.loadbalancer.PoolHealthMonitor;
import org.midonet.cluster.data.neutron.loadbalancer.VIP;
import org.midonet.cluster.data.util.ZkOpLock;
import org.midonet.cluster.rest_api.ConflictHttpException;
import org.midonet.cluster.rest_api.InternalServerErrorHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.ZkOpList;

import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;

/**
 * MidoNet implementation of Neutron plugin interface.
 */
@SuppressWarnings("unused")
public class NeutronPlugin implements NetworkApi, L3Api, SecurityGroupApi,
                                      LoadBalancerApi {

    private static final Logger log =
        LoggerFactory.getLogger(NeutronPlugin.class);

    public static final String LOCK_NAME = "neutron";
    public static final int LOCK_WAIT_SEC = 5;
    private AtomicInteger lockOpNumber = new AtomicInteger(0);

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

    private void commitOps(List<Op> ops) throws StateAccessException,
                                                StatePathExistsException {
        ZkOpList opList = new ZkOpList(zkManager);
        opList.addAll(ops);
        opList.commit();
    }

    // The following wrapper functions for locking are defined so that
    // these lock methods throw a RuntimeException instead of checked Exception
    private ZkOpLock acquireLock() {

        ZkOpLock lock = new ZkOpLock(lockFactory, lockOpNumber.getAndAdd(1),
                                     LOCK_NAME);

        lock.acquire();

        return lock;
    }

    private WebApplicationException handle(Exception e) {
        if (e instanceof SerializationException) {
            log.error("Serialization error", e);
            return new InternalServerErrorHttpException(e.getMessage());
        } else if (e instanceof StatePathExistsException) {
            log.error("Duplicate resource error", e);
            return new ConflictHttpException(e, getMessage(RESOURCE_EXISTS));
        } else if (e instanceof StateAccessException) {
            log.error("Not found", e);
            return new NotFoundHttpException(e, getMessage(RESOURCE_NOT_FOUND));
        } else {
            log.error("Unhandled exception", e);
            return new InternalServerErrorHttpException(e.getMessage());
        }
    }

    @Override
    public Network createNetwork(@Nonnull Network network)
        throws WebApplicationException {

        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
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
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }

        return getNetwork(network.id);
    }

    @Override
    public List<Network> createNetworkBulk(
        @Nonnull List<Network> networks)
        throws WebApplicationException {

        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            for (Network network : networks) {
                networkZkManager.prepareCreateNetwork(ops, network);
            }
            commitOps(ops);

        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }

        List<Network> nets = new ArrayList<>(networks.size());
        for (Network network : networks) {
            nets.add(getNetwork(network.id));
        }
        return nets;
    }

    @Override
    public void deleteNetwork(@Nonnull UUID id)
        throws WebApplicationException {

        ZkOpLock lock = acquireLock();
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
                externalNetZkManager.prepareDeleteExternalNetwork(ops, net);
            }

            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public Network getNetwork(@Nonnull UUID id) throws ConflictHttpException,
                                                       NotFoundHttpException {
        try {
            return networkZkManager.getNetwork(id);
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public List<Network> getNetworks()
        throws WebApplicationException {
        try {
            return networkZkManager.getNetworks();
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public Network updateNetwork(@Nonnull UUID id, @Nonnull Network network)
        throws WebApplicationException {

        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {

            // Note that the internal vxlan port id of the bridge is not being
            // validated here because neutron has no vxgw. But just to make it
            // explicit: the vxlan port id can NOT be modified by the user, if
            // it ever gets modified from here we should be using the same
            // validation as in the Midonet API BridgeResource.
            networkZkManager.prepareUpdateNetwork(ops, network);
            externalNetZkManager.prepareUpdateExternalNetwork(ops, network);

            // TODO: Include ZK version when updating
            // Throws NotStatePathException if it does not exist.
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }

        return getNetwork(id);
    }

    @Override
    public Subnet createSubnet(@Nonnull Subnet subnet)
        throws WebApplicationException {

        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            networkZkManager.prepareCreateSubnet(ops, subnet);

            // For external network, link the bridge to the provider router.
            Network network = getNetwork(subnet.networkId);
            if (network.external) {
                externalNetZkManager.prepareLinkToProvider(ops, subnet);
            }

            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }

        return getSubnet(subnet.id);
    }

    @Override
    public List<Subnet> createSubnetBulk(@Nonnull List<Subnet> subnets)
        throws WebApplicationException {

        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            for (Subnet subnet : subnets) {
                networkZkManager.prepareCreateSubnet(ops, subnet);
            }
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }

        List<Subnet> newSubnets = new ArrayList<>(subnets.size());
        for (Subnet subnet : subnets) {
            newSubnets.add(getSubnet(subnet.id));
        }

        return newSubnets;
    }

    @Override
    public void deleteSubnet(@Nonnull UUID id)
        throws WebApplicationException {

        ZkOpLock lock = acquireLock();
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
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public Subnet getSubnet(@Nonnull UUID id)
        throws WebApplicationException {
        try {
            return networkZkManager.getSubnet(id);
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public List<Subnet> getSubnets()
        throws WebApplicationException {
        try {
            return networkZkManager.getSubnets();
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public Subnet updateSubnet(@Nonnull UUID id, @Nonnull Subnet subnet)
        throws WebApplicationException {

        List<Op> ops = new ArrayList<>();
        Network net;
        try {
            net = networkZkManager.getNetwork(subnet.networkId);
        } catch (Exception e) {
            throw handle(e);
        }

        ZkOpLock lock = acquireLock();

        try {
            networkZkManager.prepareUpdateSubnet(ops, subnet);
            if (net.external) {
                externalNetZkManager.prepareUpdateExtSubnet(ops, subnet);
            } else {
                l3ZkManager.prepareUpdateSubnet(ops, subnet);
            }

            // This should throw NoStatePathException if it doesn't exist.
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
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
        throws WebApplicationException {

        List<Op> ops = new ArrayList<>();

        ZkOpLock lock = acquireLock();
        try {
            createPortOps(ops, port);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }

        return getPort(port.id);
    }

    @Override
    public List<Port> createPortBulk(@Nonnull List<Port> ports)
        throws WebApplicationException {

        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            for (Port port : ports) {
                createPortOps(ops, port);
            }
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }

        List<Port> outPorts = new ArrayList<>(ports.size());
        for (Port port : ports) {
            outPorts.add(getPort(port.id));
        }
        return outPorts;
    }

    @Override
    public void deletePort(@Nonnull UUID id)
        throws WebApplicationException {

        ZkOpLock lock = acquireLock();
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
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public Port getPort(@Nonnull UUID id)
        throws WebApplicationException {
        try {
            return networkZkManager.getPort(id);
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public List<Port> getPorts() throws WebApplicationException {
        try {
            return networkZkManager.getPorts();
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public Port updatePort(@Nonnull UUID id, @Nonnull Port port)
        throws WebApplicationException {

        // Fixed IP and security groups can be updated
        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
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
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }

        return getPort(id);
    }

    @Override
    public Router createRouter(@Nonnull Router router)
        throws WebApplicationException {

        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            // Create a RouterConfig in ZK
            l3ZkManager.prepareCreateRouter(ops, router);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }

        return getRouter(router.id);
    }

    @Override
    public Router getRouter(@Nonnull UUID id) throws WebApplicationException {
        try {
            return l3ZkManager.getRouter(id);
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public List<Router> getRouters() throws WebApplicationException {
        try {
            return l3ZkManager.getRouters();
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public final void deleteRouter(@Nonnull UUID id)
        throws WebApplicationException {

        ZkOpLock lock = acquireLock();
        try {
            Router router = l3ZkManager.getRouter(id);
            if (router == null) {
                return;
            }

            List<Op> ops = new ArrayList<>();
            l3ZkManager.prepareDeleteRouter(ops, id);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public Router updateRouter(@Nonnull UUID id, @Nonnull Router router)
        throws WebApplicationException {

        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            // Update the router config
            l3ZkManager.prepareUpdateRouter(ops, router);

            // This should throw NoPathExistsException if the resource does not
            // exist.
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }

        return getRouter(router.id);
    }

    @Override
    public RouterInterface addRouterInterface(
        @Nonnull UUID routerId, @Nonnull RouterInterface routerInterface)
        throws WebApplicationException {

        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            l3ZkManager.prepareCreateRouterInterface(ops, routerInterface);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
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
        throws WebApplicationException {

        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            l3ZkManager.prepareCreateFloatingIp(ops, floatingIp);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }

        return getFloatingIp(floatingIp.id);
    }

    @Override
    public FloatingIp getFloatingIp(@Nonnull UUID id)
        throws WebApplicationException {
            try {
                return l3ZkManager.getFloatingIp(id);
            } catch (Exception e) {
                throw handle(e);
            }
    }

    @Override
    public List<FloatingIp> getFloatingIps() throws WebApplicationException {
        try {
            return l3ZkManager.getFloatingIps();
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public void deleteFloatingIp(@Nonnull UUID id)
        throws WebApplicationException {

        ZkOpLock lock = acquireLock();
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
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public FloatingIp updateFloatingIp(@Nonnull UUID id,
                                       @Nonnull FloatingIp floatingIp)
        throws WebApplicationException {

        FloatingIp oldFip;
        try {
            oldFip = l3ZkManager.getFloatingIp(id);
            if (oldFip == null) {
                return null;
            }
        } catch (Exception e) {
            throw handle(e);
        }

        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            l3ZkManager.prepareUpdateFloatingIp(ops, floatingIp);
            commitOps(ops);
            return l3ZkManager.getFloatingIp(id);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public SecurityGroup createSecurityGroup(@Nonnull SecurityGroup sg)
        throws WebApplicationException {

        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            securityGroupZkManager.prepareCreateSecurityGroup(ops, sg);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }

        return getSecurityGroup(sg.id);
    }

    @Override
    public List<SecurityGroup> createSecurityGroupBulk(
        @Nonnull List<SecurityGroup> sgs)
        throws WebApplicationException {
        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            for (SecurityGroup sg : sgs) {
                securityGroupZkManager.prepareCreateSecurityGroup(ops, sg);
            }
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }

        List<SecurityGroup> newSgs = new ArrayList<>(sgs.size());
        for (SecurityGroup sg : sgs) {
            newSgs.add(getSecurityGroup(sg.id));
        }

        return newSgs;
    }

    @Override
    public void deleteSecurityGroup(@Nonnull UUID id)
        throws WebApplicationException {

        ZkOpLock lock = acquireLock();
        try {
            SecurityGroup sg = securityGroupZkManager.getSecurityGroup(id);
            if (sg == null) {
                return;
            }

            List<Op> ops = new ArrayList<>();

            securityGroupZkManager.prepareDeleteSecurityGroup(ops, id);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public SecurityGroup getSecurityGroup(@Nonnull UUID id)
        throws WebApplicationException {

        try {
            SecurityGroup sg = securityGroupZkManager.getSecurityGroup(id);
            if (sg == null) {
                return null;
            }

            // Also return security group rules.
            sg.securityGroupRules = securityGroupZkManager.getSecurityGroupRules(
                sg.id);

            return sg;
        } catch (Exception e) {
            throw handle(e);
        }

    }

    @Override
    public List<SecurityGroup> getSecurityGroups()
        throws WebApplicationException {

        try {
            List<SecurityGroup> sgs = securityGroupZkManager.getSecurityGroups();

            // Also get their rules
            for (SecurityGroup sg : sgs) {
                sg.securityGroupRules =
                    securityGroupZkManager.getSecurityGroupRules(sg.id);
            }

            return sgs;
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public SecurityGroup updateSecurityGroup(
        @Nonnull UUID id, @Nonnull SecurityGroup sg)
        throws WebApplicationException {

        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            securityGroupZkManager.prepareUpdateSecurityGroup(ops, sg);

            // This should throw NoStatePathException if it doesn't exist.
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }

        return getSecurityGroup(id);
    }

    @Override
    public SecurityGroupRule createSecurityGroupRule(
        @Nonnull SecurityGroupRule rule)
        throws WebApplicationException {

        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            securityGroupZkManager.prepareCreateSecurityGroupRule(ops, rule);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }

        return getSecurityGroupRule(rule.id);
    }

    @Override
    public List<SecurityGroupRule> createSecurityGroupRuleBulk(
        @Nonnull List<SecurityGroupRule> rules)
        throws WebApplicationException {

        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            for (SecurityGroupRule rule : rules) {
                securityGroupZkManager.prepareCreateSecurityGroupRule(ops,
                                                                      rule);
            }
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }

        List<SecurityGroupRule> newRules = new ArrayList<>(rules.size());
        for (SecurityGroupRule rule : rules) {
            newRules.add(getSecurityGroupRule(rule.id));
        }

        return newRules;
    }

    @Override
    public void deleteSecurityGroupRule(@Nonnull UUID id)
        throws WebApplicationException {

        ZkOpLock lock = acquireLock();
        try {
            SecurityGroupRule rule =
                securityGroupZkManager.getSecurityGroupRule(id);
            if (rule == null) {
                return;
            }

            List<Op> ops = new ArrayList<>();

            securityGroupZkManager.prepareDeleteSecurityGroupRule(ops, id);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public SecurityGroupRule getSecurityGroupRule(@Nonnull UUID id)
        throws WebApplicationException {
        try {
            return securityGroupZkManager.getSecurityGroupRule(id);
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public List<SecurityGroupRule> getSecurityGroupRules()
        throws WebApplicationException {
        try {
            return securityGroupZkManager.getSecurityGroupRules();

        } catch (Exception e) {
            throw handle(e);
        }
    }

    // Pools
    @Override
    public Pool getPool(UUID id) throws WebApplicationException {
        try {
            return lbZkManager.getNeutronPool(id);
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public List<Pool> getPools()
        throws WebApplicationException {
        try {
            return lbZkManager.getNeutronPools();
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public void createPool(Pool pool)
        throws WebApplicationException {
        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            lbZkManager.prepareCreatePool(ops, pool);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public void updatePool(UUID id, Pool pool)
        throws WebApplicationException {
        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            lbZkManager.prepareUpdatePool(ops, id, pool);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public void deletePool(UUID id)
        throws WebApplicationException {
        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            lbZkManager.prepareDeletePool(ops, id);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    // Members
    @Override
    public Member getMember(UUID id) throws WebApplicationException {
        try {
            return lbZkManager.getNeutronMember(id);
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public List<Member> getMembers() throws WebApplicationException {
        try {
            return lbZkManager.getNeutronMembers();
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public void createMember(Member member)
        throws WebApplicationException {
        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            lbZkManager.prepareCreateMember(ops, member);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public void updateMember(UUID id, Member member)
        throws WebApplicationException {
        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            lbZkManager.prepareUpdateMember(ops, id, member);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public void deleteMember(UUID id)
        throws WebApplicationException {
        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            lbZkManager.prepareDeleteMember(ops, id);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    // Vips    public Member getMember(UUID id)
    @Override
    public VIP getVip(UUID id)
        throws WebApplicationException {
        try {
            return lbZkManager.getNeutronVip(id);
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public List<VIP> getVips()
        throws WebApplicationException {
        try {
            return lbZkManager.getNeutronVips();
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public void createVip(VIP vip)
        throws WebApplicationException {
        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            lbZkManager.prepareCreateVip(ops, vip);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public void updateVip(UUID id, VIP vip)
        throws WebApplicationException {
        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            lbZkManager.prepareUpdateVip(ops, id, vip);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public void deleteVip(UUID id)
        throws WebApplicationException {
        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            lbZkManager.prepareDeleteVip(ops, id);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    // Health Monitors
    @Override
    public HealthMonitor getHealthMonitor(UUID id)
        throws WebApplicationException {
        try {
            return lbZkManager.getNeutronHealthMonitor(id);
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public List<HealthMonitor> getHealthMonitors()
        throws WebApplicationException {
        try {
            return lbZkManager.getNeutronHealthMonitors();
        } catch (Exception e) {
            throw handle(e);
        }
    }

    @Override
    public void createHealthMonitor(HealthMonitor healthMonitor)
        throws WebApplicationException {
        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            lbZkManager.prepareCreateHealthMonitor(ops, healthMonitor);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public void updateHealthMonitor(UUID id,
                                             HealthMonitor healthMonitor)
        throws WebApplicationException {
        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            lbZkManager.prepareUpdateHealthMonitor(ops, id, healthMonitor);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public void deleteHealthMonitor(UUID id)
        throws WebApplicationException {
        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            lbZkManager.prepareDeleteHealthMonitor(ops, id);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    // Pool Health Monitors
    @Override
    public void createPoolHealthMonitor(UUID poolId,
                                        PoolHealthMonitor poolHealthMonitor)
        throws WebApplicationException {
        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            lbZkManager.createPoolHealthMonitor(ops, poolId, poolHealthMonitor);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }

    @Override
    public void deletePoolHealthMonitor(UUID poolId, UUID hmId)
        throws WebApplicationException {
        List<Op> ops = new ArrayList<>();
        ZkOpLock lock = acquireLock();
        try {
            lbZkManager.deletePoolHealthMonitor(ops, poolId, hmId);
            commitOps(ops);
        } catch (Exception e) {
            throw handle(e);
        } finally {
            lock.release();
        }
    }
}
