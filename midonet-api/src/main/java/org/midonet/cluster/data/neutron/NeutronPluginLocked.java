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

import javax.annotation.Nonnull;

import com.google.inject.Inject;

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
import org.midonet.cluster.data.util.ZkOpLock;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;


/**
 * MidoNet implementation of Neutron plugin interface. This implementation
 * uses a ZK lock to serialize operations made by different api nodes. It only
 * overrides and locks those api calls that actually write to the nsdb.
 */
@SuppressWarnings("unused")
public class NeutronPluginLocked implements NeutronPlugin {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(NeutronPluginLocked.class);

    private static final String LOCK_NAME = "neutron";

    private static final Object mutex = new Object();

    private ZkOpLock lock;

    @Inject
    private NeutronPluginImpl plugin;

    @Inject
    public NeutronPluginLocked(ZookeeperLockFactory lockFactory) {
        lock = new ZkOpLock(lockFactory, LOCK_NAME);
    }

    @Override
    public Network createNetwork(@Nonnull Network network)
        throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<>();

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.createNetworkOp(network);
            } finally {
                lock.release();
            }
        }

        return getNetwork(network.id);
    }

    @Override
    public List<Network> createNetworkBulk(@Nonnull List<Network> networks)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.createNetworkBulkOp(networks);
            } finally {
                lock.release();
            }
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

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.deleteNetwork(id);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public Network getNetwork(@Nonnull UUID id)
        throws StateAccessException, SerializationException {
        return plugin.getNetwork(id);
    }

    @Override
    public List<Network> getNetworks()
        throws StateAccessException, SerializationException {
        return plugin.getNetworks();
    }

    @Override
    public Network updateNetwork(@Nonnull UUID id, @Nonnull Network network)
        throws StateAccessException, SerializationException,
               BridgeZkManager.VxLanPortIdUpdateException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.updateNetworkOp(id, network);
            } finally {
                lock.release();
            }
        }

        return getNetwork(id);
    }

    @Override
    public Subnet createSubnet(@Nonnull Subnet subnet)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.createSubnetOp(subnet);
            } finally {
                lock.release();
            }
        }

        return getSubnet(subnet.id);
    }

    @Override
    public List<Subnet> createSubnetBulk(@Nonnull List<Subnet> subnets)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.createSubnetBulkOp(subnets);
            } finally {
                lock.release();
            }
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

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.deleteSubnet(id);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public Subnet getSubnet(@Nonnull UUID id)
        throws StateAccessException, SerializationException {
        return plugin.getSubnet(id);
    }

    @Override
    public List<Subnet> getSubnets()
        throws StateAccessException, SerializationException {
        return plugin.getSubnets();
    }

    @Override
    public Subnet updateSubnet(@Nonnull UUID id, @Nonnull Subnet subnet)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.updateSubnetOp(id, subnet);
            } finally {
                lock.release();
            }
        }

        return getSubnet(id);
    }

    @Override
    public Port createPort(@Nonnull Port port)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.createPortOp(port);
            } finally {
                lock.release();
            }
        }

        return getPort(port.id);
    }

    @Override
    public List<Port> createPortBulk(@Nonnull List<Port> ports)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.createPortBulkOp(ports);
            } finally {
                lock.release();
            }
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

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.deletePort(id);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public Port getPort(@Nonnull UUID id)
        throws StateAccessException, SerializationException {
        return plugin.getPort(id);
    }

    @Override
    public List<Port> getPorts()
        throws StateAccessException, SerializationException {
        return plugin.getPorts();
    }

    @Override
    public Port updatePort(@Nonnull UUID id, @Nonnull Port port)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.updatePortOp(id, port);
            } finally {
                lock.release();
            }
        }

        return getPort(id);
    }

    @Override
    public Router createRouter(@Nonnull Router router)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.createRouterOp(router);
            } finally {
                lock.release();
            }
        }

        return getRouter(router.id);
    }

    @Override
    public void deleteRouter(@Nonnull UUID id)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.deleteRouter(id);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public Router getRouter(@Nonnull UUID id)
        throws StateAccessException, SerializationException {
        return plugin.getRouter(id);
    }

    @Override
    public List<Router> getRouters()
        throws StateAccessException, SerializationException {
        return plugin.getRouters();
    }

    @Override
    public Router updateRouter(@Nonnull UUID id, @Nonnull Router router)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.updateRouterOp(id, router);
            } finally {
                lock.release();
            }
        }

        return getRouter(router.id);
    }

    @Override
    public RouterInterface addRouterInterface(
        @Nonnull UUID routerId, @Nonnull RouterInterface routerInterface)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        synchronized (mutex) {
            lock.acquire();
            try {
                return plugin.addRouterInterface(routerId, routerInterface);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public RouterInterface removeRouterInterface(@Nonnull UUID routerId,
                                                 @Nonnull RouterInterface routerInterface) {
        return plugin.removeRouterInterface(routerId, routerInterface);
    }

    @Override
    public FloatingIp createFloatingIp(@Nonnull FloatingIp floatingIp)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.createFloatingIpOp(floatingIp);
            } finally {
                lock.release();
            }
        }

        return getFloatingIp(floatingIp.id);
    }

    @Override
    public void deleteFloatingIp(@Nonnull UUID id)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.deleteFloatingIp(id);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public FloatingIp getFloatingIp(@Nonnull UUID id)
        throws StateAccessException, SerializationException {
        return plugin.getFloatingIp(id);
    }

    @Override
    public List<FloatingIp> getFloatingIps()
        throws StateAccessException, SerializationException {
        return plugin.getFloatingIps();
    }

    @Override
    public FloatingIp updateFloatingIp(@Nonnull UUID id,
                                       @Nonnull FloatingIp floatingIp)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        FloatingIp oldFip = getFloatingIp(id);
        if (oldFip == null) {
            return null;
        }

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.updateFloatingIpOp(id, floatingIp);
            } finally {
                lock.release();
            }
        }

        return getFloatingIp(id);
    }

    @Override
    public SecurityGroup createSecurityGroup(@Nonnull SecurityGroup sg)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.createSecurityGroupOp(sg);
            } finally {
                lock.release();
            }
        }

        return getSecurityGroup(sg.id);
    }

    @Override
    public List<SecurityGroup> createSecurityGroupBulk(
        @Nonnull List<SecurityGroup> sgs)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.createSecurityGroupBulkOp(sgs);
            } finally {
                lock.release();
            }
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

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.deleteSecurityGroup(id);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public SecurityGroup getSecurityGroup(@Nonnull UUID id)
        throws StateAccessException, SerializationException {
        return plugin.getSecurityGroup(id);
    }

    @Override
    public List<SecurityGroup> getSecurityGroups()
        throws StateAccessException, SerializationException {
        return plugin.getSecurityGroups();
    }

    @Override
    public SecurityGroup updateSecurityGroup(
        @Nonnull UUID id, @Nonnull SecurityGroup sg)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.updateSecurityGroupOp(id, sg);
            } finally {
                lock.release();
            }
        }

        return getSecurityGroup(id);
    }

    @Override
    public SecurityGroupRule createSecurityGroupRule(
        @Nonnull SecurityGroupRule rule)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.createSecurityGroupRuleOp(rule);
            } finally {
                lock.release();
            }
        }

        return getSecurityGroupRule(rule.id);
    }

    @Override
    public List<SecurityGroupRule> createSecurityGroupRuleBulk(
        @Nonnull List<SecurityGroupRule> rules)
        throws StateAccessException, SerializationException,
               Rule.RuleIndexOutOfBoundsException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.createSecurityGroupRuleBulkOp(rules);
            } finally {
                lock.release();
            }
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

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.deleteSecurityGroupRule(id);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public SecurityGroupRule getSecurityGroupRule(@Nonnull UUID id)
        throws StateAccessException, SerializationException {
        return plugin.getSecurityGroupRule(id);
    }

    @Override
    public List<SecurityGroupRule> getSecurityGroupRules()
        throws StateAccessException, SerializationException {
        return plugin.getSecurityGroupRules();
    }

    @Override
    public Pool getPool(UUID id)
        throws StateAccessException, SerializationException {
        return plugin.getPool(id);
    }

    @Override
    public List<Pool> getPools()
        throws StateAccessException, SerializationException {
        return plugin.getPools();
    }

    @Override
    public void createPool(Pool pool)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.createPool(pool);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public void updatePool(UUID id, Pool pool)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.updatePool(id, pool);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public void deletePool(UUID id)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.deletePool(id);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public Member getMember(UUID id)
        throws StateAccessException, SerializationException {
        return plugin.getMember(id);
    }

    @Override
    public List<Member> getMembers()
        throws StateAccessException, SerializationException {
        return plugin.getMembers();
    }

    @Override
    public void createMember(Member member)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.createMember(member);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public void updateMember(UUID id, Member member)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.updateMember(id, member);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public void deleteMember(UUID id)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.deleteMember(id);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public VIP getVip(UUID id)
        throws StateAccessException, SerializationException {
        return plugin.getVip(id);
    }

    @Override
    public List<VIP> getVips()
        throws StateAccessException, SerializationException {
        return plugin.getVips();
    }

    @Override
    public void createVip(VIP vip)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.createVip(vip);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public void updateVip(UUID id, VIP vip)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.updateVip(id, vip);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public void deleteVip(UUID id)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.deleteVip(id);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public HealthMonitor getHealthMonitor(UUID id)
        throws StateAccessException, SerializationException {
        return plugin.getHealthMonitor(id);
    }

    @Override
    public List<HealthMonitor> getHealthMonitors()
        throws StateAccessException, SerializationException {
        return plugin.getHealthMonitors();
    }

    @Override
    public void createHealthMonitor(HealthMonitor healthMonitor)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.createHealthMonitor(healthMonitor);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public void updateHealthMonitor(UUID id, HealthMonitor healthMonitor)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.updateHealthMonitor(id, healthMonitor);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public void deleteHealthMonitor(UUID id)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.deleteHealthMonitor(id);
            } finally {
                lock.release();
            }
        }

    }

    // Pool Health Monitors
    @Override
    public void createPoolHealthMonitor(UUID poolId,
                                        PoolHealthMonitor poolHealthMonitor)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.createPoolHealthMonitor(poolId, poolHealthMonitor);
            } finally {
                lock.release();
            }
        }

    }

    @Override
    public void deletePoolHealthMonitor(UUID poolId, UUID hmId)
        throws StateAccessException, SerializationException {

        synchronized (mutex) {
            lock.acquire();
            try {
                plugin.deletePoolHealthMonitor(poolId, hmId);
            } finally {
                lock.release();
            }
        }

    }
}
