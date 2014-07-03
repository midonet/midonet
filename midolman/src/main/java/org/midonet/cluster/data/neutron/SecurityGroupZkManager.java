/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import com.google.common.base.Objects;
import com.google.inject.Inject;
import org.apache.zookeeper.Op;
import org.midonet.midolman.rules.JumpRule;
import org.midonet.midolman.rules.Rule;
import org.midonet.midolman.rules.RuleBuilder;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.*;
import org.midonet.midolman.state.zkManagers.ChainZkManager;
import org.midonet.midolman.state.zkManagers.ChainZkManager.ChainConfig;
import org.midonet.midolman.state.zkManagers.IpAddrGroupZkManager;
import org.midonet.midolman.state.zkManagers.IpAddrGroupZkManager.IpAddrGroupConfig;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.midolman.state.zkManagers.RuleZkManager;
import org.midonet.packets.IPSubnet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class SecurityGroupZkManager extends BaseZkManager {

    private final ChainZkManager chainZkManager;
    private final IpAddrGroupZkManager ipAddrGroupZkManager;
    private final NetworkZkManager networkZkManager;
    private final PortZkManager portZkManager;
    private final RuleZkManager ruleZkManager;

    @Inject
    public SecurityGroupZkManager(ZkManager zk,
                                  PathBuilder paths,
                                  Serializer serializer,
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
    }

    private void prepareCreateChain(List<Op> ops, UUID chainId, String name, List<Rule> rules)
            throws SerializationException, StateAccessException {

        ChainConfig config = new ChainConfig(name);
        chainZkManager.prepareCreate(ops, chainId, config);
        ruleZkManager.prepareCreateRulesInNewChain(ops, chainId, rules);
    }

    private void preparePortChains(List<Op> ops, Port port, UUID inboundChainId,
                                   UUID outboundChainId)
            throws StateAccessException, SerializationException {

        List<Rule> inRules = new ArrayList<>();
        List<Rule> outRules = new ArrayList<>();

        putPortChainRules(inRules, outRules, port, inboundChainId,
                outboundChainId);

        String name = Port.egressChainName(port.id);
        prepareCreateChain(ops, inboundChainId, name, inRules);

        name = Port.ingressChainName(port.id);
        prepareCreateChain(ops, outboundChainId, name, outRules);
    }

    private Rule createSgJumpRule(IpAddrGroupConfig gc, RuleDirection dir, UUID chainId)
            throws StateAccessException, SerializationException {
        UUID jumpChainId = gc.getPropertyUuid(dir);
        ChainConfig jChain = chainZkManager.get(jumpChainId);
        return new JumpRule(chainId, jumpChainId, jChain.name);
    }

    private void putPortChainRules(List<Rule> inRules, List<Rule> outRules, Port port,
                                   UUID inChainId, UUID outChainId)
            throws SerializationException, StateAccessException {

        inRules.clear();
        outRules.clear();

        // Add reverse flow matching for out_chain.
        outRules.add(new RuleBuilder(outChainId)
            .isReturnFlow()
            .accept());

        // IP spoofing protection for in_chain
        for (IPAllocation fixedIp : port.fixedIps) {

            Subnet subnet = networkZkManager.getSubnet(fixedIp.subnetId);
            IPSubnet ipSub = subnet.isIpv4()
                    ? fixedIp.ipv4Subnet() : fixedIp.ipv6Subnet();
            Rule ipSpoofProtectionRule = new RuleBuilder(inChainId)
                .notFromSubnet(ipSub)
                .drop();
            inRules.add(ipSpoofProtectionRule);
        }

        // MAC spoofing protection for in_chain
        Rule macSpoofProtectionRule = new RuleBuilder(inChainId)
            .notFromMac(port.macAddress())
            .drop();
        inRules.add(macSpoofProtectionRule);

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
        inRules.add(new RuleBuilder(inChainId).notARP().drop());
        outRules.add(new RuleBuilder(outChainId).notARP().drop());
    }

    private void prepareUpdatePortChains(List<Op> ops, Port port, PortConfig cfg)
            throws SerializationException, StateAccessException {

        List<Rule> inRules = new ArrayList<>();
        List<Rule> outRules = new ArrayList<>();

        putPortChainRules(inRules, outRules, port, cfg.inboundFilter,
                cfg.outboundFilter);

        ruleZkManager.prepareReplaceRules(ops, cfg.inboundFilter, inRules);
        ruleZkManager.prepareReplaceRules(ops, cfg.outboundFilter, outRules);
    }

    private void preparePortSecurityGroupBindings(List<Op> ops, Port port, boolean isRebuild)
            throws SerializationException, StateAccessException {

        // Bind port to security groups
        for (UUID sgId : port.securityGroups) {
            // Add each IPs assigned to the ip address group
            for (IPAllocation ipAlloc : port.fixedIps) {
                ipAddrGroupZkManager.prepareAddAdr(ops, sgId,
                        ipAlloc.ipAddress, port.id, isRebuild);
            }
        }
    }

    public void preparePortSecurityGroupBindings(List<Op> ops, Port port, PortConfig portConfig)
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
                        ipAlloc.ipAddress, port.id);
            }
        }
    }

    public void prepareDeletePortSecurityGroup(List<Op> ops, Port port)
            throws SerializationException, StateAccessException {

        PortConfig cfg = portZkManager.get(port.id);
        prepareDeletePortSecurityGroupBindings(ops, port);

        // Remove the port chains
        prepareDeletePortChains(ops, cfg);
    }

    public void prepareUpdatePortSecurityGroupBindings(List<Op> ops, Port newPort)
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

        SecurityGroup sg = getSecurityGroup(sgId);
        if (sg == null) {
            return;
        }

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
        ChainConfig cfg = new ChainConfig(sg.ingressChainName());
        ops.addAll(chainZkManager.prepareCreate(outboundChainId, cfg));

        UUID inboundChainId = UUID.randomUUID();
        cfg = new ChainConfig(sg.egressChainName());
        ops.addAll(chainZkManager.prepareCreate(inboundChainId, cfg));

        // Create an IP address group for this security group. Save the chain
        // IDs in the properties so that there is a way to get the chains from
        // IP address group.
        IpAddrGroupConfig config = new IpAddrGroupConfig(sg.id, sg.name);
        config.putProperty(RuleDirection.INGRESS, outboundChainId);
        config.putProperty(RuleDirection.EGRESS, inboundChainId);

        ops.addAll(ipAddrGroupZkManager.prepareCreate(sg.id, config));

        String path = paths.getNeutronSecurityGroupPath(sg.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(sg)));

        List<Rule> ingressRules = new ArrayList<>();
        List<Rule> egressRules = new ArrayList<>();

        for (SecurityGroupRule sgr : sg.securityGroupRules) {
            if (sgr.isEgress()) {
                Rule r = new RuleBuilder(inboundChainId)
                    .securityGroupRule(sgr)
                    .accept();
                egressRules.add(r);
            } else {
                Rule r = new RuleBuilder(outboundChainId)
                    .securityGroupRule(sgr)
                    .accept();
                ingressRules.add(r);
            }
            String rulePath = paths.getNeutronSecurityGroupRulePath(sgr.id);
            ops.add(zk.getPersistentCreateOp(rulePath, serializer.serialize(sgr)));
        }

        ruleZkManager.prepareCreateRulesInNewChain(ops, inboundChainId, egressRules);
        ruleZkManager.prepareCreateRulesInNewChain(ops, outboundChainId, ingressRules);

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

        String path= paths.getNeutronSecurityGroupsPath();
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
            if (sgId == null || Objects.equal(rule.securityGroupId, sgId)) {
                rules.add(rule);
            }
        }

        return rules;
    }

    public void prepareCreateSecurityGroupRule(List<Op> ops, SecurityGroupRule rule)
            throws SerializationException, StateAccessException,
            org.midonet.cluster.data.Rule.RuleIndexOutOfBoundsException {

        // Get the IP address group so that we can retrieve the chains
        IpAddrGroupConfig ipAddrGroupConfig = ipAddrGroupZkManager.get(
                rule.securityGroupId);

        RuleDirection dir =  rule.isIngress() ?
                RuleDirection.INGRESS : RuleDirection.EGRESS;
        UUID chainId = ipAddrGroupConfig.getPropertyUuid(dir);

        Rule r = new RuleBuilder(chainId)
            .securityGroupRule(rule)
            .accept();
        ops.addAll(ruleZkManager.prepareInsertPositionOrdering(rule.id, r, 1));

        String path = paths.getNeutronSecurityGroupRulePath(rule.id);
        ops.add(zk.getPersistentCreateOp(path, serializer.serialize(rule)));
    }

    public void prepareDeleteSecurityGroupRule(List<Op> ops, UUID ruleId)
            throws StateAccessException, SerializationException {

        SecurityGroupRule rule = getSecurityGroupRule(ruleId);
        if (rule == null) {
            return;
        }

        ops.addAll(ruleZkManager.prepareDelete(ruleId));
        String path = paths.getNeutronSecurityGroupRulePath(ruleId);
        ops.add(zk.getDeleteOp(path));
    }
}
