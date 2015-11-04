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
import java.util.Set;
import java.util.UUID;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.inject.Inject;

import org.apache.zookeeper.Op;

import org.midonet.midolman.rules.JumpRule;
import org.midonet.midolman.rules.Rule;
import org.midonet.midolman.rules.RuleBuilder;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.BaseZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.ChainZkManager;
import org.midonet.midolman.state.zkManagers.ChainZkManager.ChainConfig;
import org.midonet.midolman.state.zkManagers.IpAddrGroupZkManager;
import org.midonet.midolman.state.zkManagers.IpAddrGroupZkManager.IpAddrGroupConfig;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.midolman.state.zkManagers.RuleZkManager;
import org.midonet.packets.IPSubnet;

public class SecurityGroupZkManager extends BaseZkManager {

    private final ChainZkManager chainZkManager;
    private final IpAddrGroupZkManager ipAddrGroupZkManager;
    private final NetworkZkManager networkZkManager;
    private final PortZkManager portZkManager;
    private final RuleZkManager ruleZkManager;
    private final BridgeZkManager bridgeZkManager;

    @Inject
    public SecurityGroupZkManager(ZkManager zk,
                                  PathBuilder paths,
                                  Serializer serializer,
                                  BridgeZkManager bridgeZkManager,
                                  ChainZkManager chainZkManager,
                                  IpAddrGroupZkManager ipAddrGroupZkManager,
                                  NetworkZkManager networkZkManager,
                                  PortZkManager portZkManager,
                                  RuleZkManager ruleZkManager) {
        super(zk, paths, serializer);
        this.chainZkManager = chainZkManager;
        this.ipAddrGroupZkManager = ipAddrGroupZkManager;
        this.networkZkManager = networkZkManager;
        this.portZkManager = portZkManager;
        this.ruleZkManager = ruleZkManager;
        this.bridgeZkManager = bridgeZkManager;
    }

    private void prepareCreateChain(List<Op> ops, UUID chainId, String name,
                                    String tenantId, List<Rule> rules)
        throws SerializationException, StateAccessException {

        ChainConfig config = new ChainConfig(name);
        config.setTenantId(tenantId);
        chainZkManager.prepareCreate(ops, chainId, config);
        if (rules != null) {
            ruleZkManager.prepareCreateRulesInNewChain(ops, chainId, rules);
        }
    }

    private void prepareCreateChain(List<Op> ops, UUID chainId, String name,
                                    String tenantId)
        throws SerializationException, StateAccessException {

        prepareCreateChain(ops, chainId, name, tenantId, null);
    }

    private void preparePortChains(List<Op> ops, Port port, UUID inboundChainId,
                                   UUID outboundChainId)
        throws StateAccessException, SerializationException {

        List<Rule> inRules = new ArrayList<>();
        List<Rule> outRules = new ArrayList<>();

        putPortChainRules(inRules, outRules, port, inboundChainId,
                          outboundChainId);

        String name = Port.egressChainName(port.id);
        prepareCreateChain(ops, inboundChainId, name, port.tenantId, inRules);

        name = Port.ingressChainName(port.id);
        prepareCreateChain(ops, outboundChainId, name, port.tenantId, outRules);
    }

    private Rule createSgJumpRule(IpAddrGroupConfig gc, RuleDirection dir,
                                  UUID chainId)
        throws StateAccessException, SerializationException {
        UUID jumpChainId = gc.getPropertyUuid(dir);
        ChainConfig jChain = chainZkManager.get(jumpChainId);
        return new RuleBuilder(chainId).jumpTo(jumpChainId, jChain.name);
    }

    private void putPortChainRules(List<Rule> inRules, List<Rule> outRules,
                                   Port port,
                                   UUID inChainId, UUID outChainId)
        throws SerializationException, StateAccessException {

        inRules.clear();
        outRules.clear();

        // Add reverse flow matching for out_chain.
        outRules.add(new RuleBuilder(outChainId)
                         .isReturnFlow()
                         .accept());

        BridgeZkManager.BridgeConfig bridgeConfig = bridgeZkManager.get(port.networkId);
        if (!bridgeConfig.disableAntiSpoof) {
            // IP spoofing protection for in_chain
            for (IPAllocation fixedIp : port.fixedIps) {

                Subnet subnet = networkZkManager.getSubnet(fixedIp.subnetId);
                IPSubnet ipSub = subnet.isIpv4()
                        ? fixedIp.ipv4Subnet() : fixedIp.ipv6Subnet();
                Rule ipSpoofProtectionRule = new RuleBuilder(inChainId)
                        .isAnyFragmentState()
                        .notFromSubnet(ipSub)
                        .drop();
                inRules.add(ipSpoofProtectionRule);
            }

            // MAC spoofing protection for in_chain
            Rule macSpoofProtectionRule = new RuleBuilder(inChainId)
                    .notFromMac(port.macAddress())
                    .isAnyFragmentState()
                    .drop();
            inRules.add(macSpoofProtectionRule);
        }

        // Add reverse flow matching for in_chain.
        inRules.add(new RuleBuilder(inChainId)
                        .isReturnFlow()
                        .accept());

        // Add jump rules for security groups
        if (port.securityGroups != null) {
            for (UUID sgId : port.securityGroups) {
                IpAddrGroupConfig gc = ipAddrGroupZkManager.get(sgId);
                inRules.add(createSgJumpRule(gc, RuleDirection.EGRESS,
                                             inChainId));
                outRules.add(createSgJumpRule(gc, RuleDirection.INGRESS,
                                              outChainId));
            }
        }

        // Both chains drop non-ARP traffic if no other rules match.
        Rule inFinalDrop = new RuleBuilder(inChainId)
            .notARP()
            .isAnyFragmentState()
            .drop();

        Rule outFinalDrop = new RuleBuilder(outChainId)
            .notARP()
            .isAnyFragmentState()
            .drop();

        inRules.add(inFinalDrop);
        outRules.add(outFinalDrop);
    }

    private void prepareUpdatePortChains(List<Op> ops, Port port,
                                         PortConfig cfg)
        throws SerializationException, StateAccessException {

        List<Rule> inRules = new ArrayList<>();
        List<Rule> outRules = new ArrayList<>();

        putPortChainRules(inRules, outRules, port, cfg.inboundFilter,
                          cfg.outboundFilter);

        ruleZkManager.prepareReplaceRules(ops, cfg.inboundFilter, inRules);
        ruleZkManager.prepareReplaceRules(ops, cfg.outboundFilter, outRules);
    }

    private void preparePortSecurityGroupBindings(List<Op> ops, Port port,
                                                  boolean isRebuild)
        throws SerializationException, StateAccessException {

        // Bind port to security groups
        for (UUID sgId : port.securityGroups) {
            // Add each IPs assigned to the ip address group
            for (IPAllocation ipAlloc : port.fixedIps) {
                ipAddrGroupZkManager.prepareAddAdr(ops, sgId,
                                                   ipAlloc.ipAddress, port.id,
                                                   isRebuild);
            }
        }
    }

    public void preparePortSecurityGroupBindings(List<Op> ops, Port port,
                                                 PortConfig portConfig)
        throws StateAccessException, SerializationException {

        // Must be VIF port
        UUID inboundChainId = UUID.randomUUID();
        UUID outboundChainId = UUID.randomUUID();

        // Initialize the chains for this port
        preparePortChains(ops, port, inboundChainId, outboundChainId);

        // Remove port - SG bindings
        preparePortSecurityGroupBindings(ops, port, false);

        // Update the port with the chains
        portConfig.inboundFilter = inboundChainId;
        portConfig.outboundFilter = outboundChainId;
        String path = paths.getPortPath(port.id);
        ops.add(zk.getSetDataOp(path, serializer.serialize(portConfig)));
    }

    private void prepareDeletePortChains(List<Op> ops, PortConfig cfg)
        throws SerializationException, StateAccessException {
        // Delete all the rules in the chain plus the chain itself
        if (cfg.inboundFilter != null) {
            ops.addAll(chainZkManager.prepareDelete(cfg.inboundFilter));
        }

        if (cfg.outboundFilter != null) {
            ops.addAll(chainZkManager.prepareDelete(cfg.outboundFilter));
        }
    }

    private void prepareDeletePortSecurityGroupBindings(List<Op> ops, Port port)
        throws SerializationException, StateAccessException {

        // Removing the IPs from the IP address group.  Note that the
        // jump rules are not deleted here because they are taken cared
        // of when the port chains are deleted.
        for (UUID sgId : port.securityGroups) {
            for (IPAllocation ipAlloc : port.fixedIps) {
                ipAddrGroupZkManager.prepareRemoveAddr(ops, sgId,
                                                       ipAlloc.ipAddress,
                                                       port.id);
            }
        }
    }

    public void prepareDeletePortSecurityGroup(List<Op> ops, Port port)
        throws SerializationException, StateAccessException {

        prepareDeletePortSecurityGroupBindings(ops, port);

        // Remove the port chains
        PortConfig cfg = portZkManager.tryGet(port.id);
        // Sanity check to make sure the port config wasn't deleted oustide of
        // neutron
        if (cfg != null) {
            prepareDeletePortChains(ops, cfg);
        }
    }

    public void prepareUpdatePortSecurityGroupBindings(List<Op> ops,
                                                       Port newPort)
        throws StateAccessException, SerializationException {

        Port p = networkZkManager.getPort(newPort.id);
        PortConfig config = portZkManager.get(newPort.id);

        // It is assumed that in the Neutron side, this field has been
        // modified to remove invalid SGs that may belong to other tenants.
        prepareUpdatePortChains(ops, newPort, config);

        // TODO: optimize
        prepareDeletePortSecurityGroupBindings(ops, p);
        preparePortSecurityGroupBindings(ops, newPort, true);
    }

    public void prepareDeleteSecurityGroup(List<Op> ops, UUID sgId)
        throws SerializationException, StateAccessException {
        Preconditions.checkNotNull(sgId);

        IpAddrGroupConfig group = ipAddrGroupZkManager.get(sgId);

        // Delete the IP Address Group.
        ops.addAll(ipAddrGroupZkManager.prepareDelete(sgId));

        // Delete the chains
        UUID inboundChainId = group.getPropertyUuid(RuleDirection.EGRESS);
        ops.addAll(chainZkManager.prepareDelete(inboundChainId));

        UUID outboundChainId = group.getPropertyUuid(RuleDirection.INGRESS);
        ops.addAll(chainZkManager.prepareDelete(outboundChainId));

        String path = paths.getNeutronSecurityGroupPath(sgId);
        ops.add(zk.getDeleteOp(path));
    }

    public void prepareUpdateSecurityGroup(List<Op> ops,
                                           SecurityGroup newSg)
        throws SerializationException {
        String path = paths.getNeutronSecurityGroupPath(newSg.id);
        ops.add(zk.getSetDataOp(path, serializer.serialize(newSg)));
    }

    public void prepareCreateSecurityGroup(List<Op> ops, SecurityGroup sg)
        throws SerializationException, StateAccessException {

        // Create two chains for a security group, representing ingress and
        // egress directions.  Use the name field to associate them back to SG.
        // Note that 'ingress' is 'outbound' in midonet.
        UUID outboundChainId = UUID.randomUUID();
        UUID inboundChainId = UUID.randomUUID();
        prepareCreateChain(ops, outboundChainId, sg.ingressChainName(),
                           sg.tenantId);
        prepareCreateChain(ops, inboundChainId, sg.egressChainName(),
                           sg.tenantId);

        // Create an IP address group for this security group. Save the chain
        // IDs in the properties so that there is a way to get the chains from
        // IP address group.
        IpAddrGroupConfig config = new IpAddrGroupConfig(sg.id, sg.name);
        config.setProperty(RuleDirection.INGRESS, outboundChainId);
        config.setProperty(RuleDirection.EGRESS, inboundChainId);

        ops.addAll(ipAddrGroupZkManager.prepareCreate(sg.id, config));

        String path = paths.getNeutronSecurityGroupPath(sg.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(sg)));

        List<Rule> ingressRules = new ArrayList<>();
        List<Rule> egressRules = new ArrayList<>();

        for (SecurityGroupRule sgr : sg.securityGroupRules) {
            if (sgr.isEgress()) {
                Rule r = new RuleBuilder(sgr.id, inboundChainId)
                    .securityGroupRule(sgr)
                    .accept();
                egressRules.add(r);
            } else {
                Rule r = new RuleBuilder(sgr.id, outboundChainId)
                    .securityGroupRule(sgr)
                    .accept();
                ingressRules.add(r);
            }
            String rulePath = paths.getNeutronSecurityGroupRulePath(sgr.id);
            ops.add(
                zk.getPersistentCreateOp(rulePath, serializer.serialize(sgr)));
        }

        ruleZkManager
            .prepareCreateRulesInNewChain(ops, inboundChainId, egressRules);
        ruleZkManager
            .prepareCreateRulesInNewChain(ops, outboundChainId, ingressRules);

    }

    public SecurityGroup getSecurityGroup(UUID securityGroupId)
        throws StateAccessException, SerializationException {

        String path = paths.getNeutronSecurityGroupPath(securityGroupId);
        if (!zk.exists(path)) {
            return null;
        }

        return serializer.deserialize(zk.get(path), SecurityGroup.class);
    }

    public List<SecurityGroup> getSecurityGroups()
        throws StateAccessException, SerializationException {

        String path = paths.getNeutronSecurityGroupsPath();
        Set<UUID> sgIds = getUuidSet(path);
        List<SecurityGroup> sgs = new ArrayList<>();
        for (UUID sgId : sgIds) {
            sgs.add(getSecurityGroup(sgId));
        }

        return sgs;
    }

    public SecurityGroupRule getSecurityGroupRule(UUID ruleId)
        throws StateAccessException, SerializationException {

        String path = paths.getNeutronSecurityGroupRulePath(ruleId);
        if (!zk.exists(path)) {
            return null;
        }

        return serializer.deserialize(zk.get(path), SecurityGroupRule.class);
    }

    public List<SecurityGroupRule> getSecurityGroupRules()
        throws StateAccessException, SerializationException {
        return getSecurityGroupRules(null);
    }

    public List<SecurityGroupRule> getSecurityGroupRules(UUID sgId)
        throws StateAccessException, SerializationException {

        String path = paths.getNeutronSecurityGroupRulesPath();
        Set<UUID> ids = getUuidSet(path);
        List<SecurityGroupRule> rules = new ArrayList<>();
        for (UUID id : ids) {
            SecurityGroupRule rule = getSecurityGroupRule(id);
            if (rule != null &&
                (sgId == null || Objects.equal(rule.securityGroupId, sgId))) {
                rules.add(rule);
            }
        }

        return rules;
    }

    public void prepareCreateSecurityGroupRule(List<Op> ops,
                                               SecurityGroupRule rule)
        throws SerializationException, StateAccessException,
               org.midonet.cluster.data.Rule.RuleIndexOutOfBoundsException {

        // Get the IP address group so that we can retrieve the chains
        IpAddrGroupConfig ipAddrGroupConfig = ipAddrGroupZkManager.get(
            rule.securityGroupId);

        RuleDirection dir = rule.isIngress() ?
                            RuleDirection.INGRESS : RuleDirection.EGRESS;
        UUID chainId = ipAddrGroupConfig.getPropertyUuid(dir);

        Rule r = new RuleBuilder(rule.id, chainId)
            .securityGroupRule(rule)
            .accept();
        ops.addAll(ruleZkManager.prepareInsertPositionOrdering(rule.id, r, 1));

        String path = paths.getNeutronSecurityGroupRulePath(rule.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(rule)));
    }

    public void prepareDeleteSecurityGroupRule(List<Op> ops, UUID ruleId)
        throws StateAccessException, SerializationException {
        Preconditions.checkNotNull(ruleId);

        ops.addAll(ruleZkManager.prepareDelete(ruleId));
        String path = paths.getNeutronSecurityGroupRulePath(ruleId);
        ops.add(zk.getDeleteOp(path));
    }
}
