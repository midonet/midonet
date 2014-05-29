/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.rules;

import java.util.*;

import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;
import org.midonet.cluster.data.neutron.SecurityGroupRule;
import org.midonet.packets.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.layer4.NatMapping;
import org.midonet.midolman.rules.RuleResult.Action;


@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
    property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = LiteralRule.class, name = "Literal"),
    @JsonSubTypes.Type(value = JumpRule.class, name = "Jump"),
    @JsonSubTypes.Type(value = ForwardNatRule.class, name = "ForwardNat"),
    @JsonSubTypes.Type(value = ReverseNatRule.class, name = "ReverseNat")
})
public abstract class Rule {
    private final static Logger log = LoggerFactory.getLogger(Rule.class);

    private Condition condition;
    public Action action;
    public UUID chainId;
    private Map<String, String> properties = new HashMap<String, String>();

    public Rule(Condition condition, Action action) {
        this(condition, action, null, -1);
    }

    public Rule(Condition condition, Action action, UUID chainId, int position) {
        this.condition = condition;
        this.action = action;
        this.chainId = chainId;
    }

    // Default constructor for the Jackson deserialization.
    public Rule() {
        super();
    }

    // Setter for Jackson serialization
    @SuppressWarnings("unused")
    private void setCondition(Condition cond) {
        this.condition = cond;
    }

    /**
     * If the packet specified by res.pmatch matches this rule's condition,
     * apply the rule.
     *
     * @param fwdInfo      the PacketContext for the packet being processed
     * @param res          contains a match of the packet after all transformations
     *                     preceding this rule. This may be modified.
     * @param natMapping   NAT state of the element using this chain.
     * @param isPortFilter whether the rule is being processed in a port filter context
     */
    public void process(ChainPacketContext fwdInfo, RuleResult res,
                        NatMapping natMapping, boolean isPortFilter) {
        if (condition.matches(fwdInfo, res.pmatch, isPortFilter)) {
            log.debug("Condition matched");
            apply(fwdInfo, res, natMapping);
        }
    }

    public Condition getCondition() {
        return condition;
    }

    /**
     * Apply this rule to the packet specified by res.pmatch.
     *
     * @param fwdInfo    the PacketContext for the packet being processed.
     * @param res        contains a match of the packet after all transformations
     *                   preceding this rule. This may be modified.
     * @param natMapping NAT state of the element using this chain.
     */
    protected abstract void apply(ChainPacketContext fwdInfo,
                                  RuleResult res, NatMapping natMapping);

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    @Override
    public int hashCode() {
        int hash = condition.hashCode() * 23;
        if (null != action)
            hash += action.hashCode();
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof Rule))
            return false;
        Rule r = (Rule) other;
        if (!condition.equals(r.condition))
            return false;
        if (null == action || null == r.action) {
            return action == r.action;
        } else {
            return action.equals(r.action);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Rule[");
        sb.append("condition=").append(condition);
        sb.append(", action=").append(action);
        sb.append(", chainId=").append(chainId);
        sb.append("]");
        return sb.toString();
    }

    // Useful factory methods
    public static Rule acceptRule(Condition cond, UUID chainId) {
        Rule cfg = new LiteralRule(cond, RuleResult.Action.ACCEPT);
        cfg.chainId = chainId;
        return cfg;
    }

    public static Rule dropRule(Condition cond, UUID chainId) {
        Rule cfg = new LiteralRule(cond, RuleResult.Action.DROP);
        cfg.chainId = chainId;
        return cfg;
    }

    public static Rule acceptRule(SecurityGroupRule sgRule, UUID chainId) {
        return acceptRule(new Condition(sgRule), chainId);
    }

    public static Rule acceptReturnFlowRule(UUID chainId) {
        Condition cond = new Condition();
        cond.matchReturnFlow = true;
        return acceptRule(cond, chainId);
    }

    public static Rule ipSpoofProtectionRule(IPSubnet subnet, UUID chainId) {
        Condition cond = new Condition(subnet);
        cond.nwSrcInv = true;
        return dropRule(cond, chainId);
    }

    public static Rule macSpoofProtectionRule(MAC macAddress, UUID chainId) {
        // MAC spoofing protection for in_chain
        Condition cond = new Condition(macAddress);
        cond.invDlSrc = true;
        return dropRule(cond, chainId);
    }

    public static Rule dropAllExceptArpRule(UUID chainId) {
        Condition cond = new Condition();
        cond.dlType = (int) ARP.ETHERTYPE;
        cond.invDlType = true;
        return dropRule(cond, chainId);
    }

    public static Rule dynamicSnatRule(UUID chainId, UUID portId,
                                       IPv4Addr addr) {
        return dynamicSnatRule(chainId, portId, new NatTarget(addr));
    }

    public static Rule dynamicSnatRule(UUID chainId, UUID portId, NatTarget t) {
        Condition cond = new Condition();
        cond.outPortIds = new HashSet<>();
        cond.outPortIds.add(portId);
        Set<NatTarget> targets = new HashSet<>();
        targets.add(t);
        return new ForwardNatRule(cond, RuleResult.Action.ACCEPT, chainId, 1,
                false, targets);
    }

    public static Rule staticSnatRule(UUID chainId, UUID portId,
                                      IPv4Addr src, IPv4Addr target) {
        return staticSnatRule(chainId, portId, new NatTarget(src, target));
    }

    public static Rule staticSnatRule(UUID chainId, UUID portId, NatTarget t) {
        Condition cond = new Condition();
        cond.outPortIds = new HashSet<>();
        cond.outPortIds.add(portId);
        Set<NatTarget> targets = new HashSet<>();
        targets.add(t);
        return new ForwardNatRule(cond, RuleResult.Action.ACCEPT, chainId, 1,
                false, targets);
    }

    public static Rule staticDnatRule(UUID chainId, UUID portId,
                                      IPv4Addr src, IPv4Addr target) {
        return staticDnatRule(chainId, portId, new NatTarget(src, target));
    }

    public static Rule staticDnatRule(UUID chainId, UUID portId, NatTarget t) {
        Condition cond = new Condition();
        cond.inPortIds = new HashSet<>();
        cond.inPortIds.add(portId);
        Set<NatTarget> targets = new HashSet<>();
        targets.add(t);
        return new ForwardNatRule(cond, RuleResult.Action.ACCEPT, chainId, 1,
                true, targets);
    }

    public static Rule reverseSnatRule(UUID chainId, UUID portId, IPv4Addr ip) {
        Condition cond = new Condition();
        cond.nwDstIp = new IPv4Subnet(ip, 32);
        cond.inPortIds = new HashSet<>();
        cond.inPortIds.add(portId);
        Rule cfg = new ReverseNatRule(cond, RuleResult.Action.ACCEPT, false);
        cfg.chainId = chainId;
        return cfg;
    }
}
